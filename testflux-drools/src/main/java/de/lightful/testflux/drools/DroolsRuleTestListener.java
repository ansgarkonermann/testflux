/******************************************************************************
 * Copyright (c) 2010 Ansgar Konermann                                        *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");  you       *
 * may not use this file except in compliance with the License. You may       *
 * obtain a copy of the License at                                            *
 *                                                                            *
 *              http://www.apache.org/licenses/LICENSE-2.0                    *
 *                                                                            *
 * Unless required by  applicable law or agreed to in writing, software       *
 * distributed under  the License is distributed  on an "AS IS"  BASIS,       *
 * WITHOUT  WARRANTIES  OR  CONDITIONS  OF  ANY  KIND,   either express       *
 * or implied.  See the License for  the  specific  language  governing       *
 * permissions and limitations under the License.                             *
 ******************************************************************************/

package de.lightful.testflux.drools;

import de.lightful.testflux.drools.impl.IterableAdapter;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.drools.KnowledgeBase;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderError;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.io.ResourceFactory;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.annotations.NoInjection;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DroolsRuleTestListener implements ITestListener {

  public DroolsRuleTestListener() {
    log.debug("Creating new instance of " + this.getClass().getName() + ".");
  }

  private static Logger log = Logger.getLogger(DroolsRuleTestListener.class);

  @Override
  public void onTestStart(ITestResult result) {
    final Class<?> realTestClass = obtainJavaTestClass(result);
    final Method realTestMethod = obtainJavaTestMethod(result);

    KnowledgeBuilder knowledgeBuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
    KnowledgeBase knowledgeBaseForClass = createNewKnowledgeBaseInstanceForClass(realTestClass, knowledgeBuilder);
    KnowledgeBase knowledgeBaseForMethod = completeKnowledgeBaseForMethod(knowledgeBaseForClass, realTestMethod, knowledgeBaseForClass);

    injectKnowledgeBase(realTestClass, result.getInstance(), knowledgeBaseForMethod, result);
  }

  private void injectKnowledgeBase(Class<?> realTestClass, Object testInstance, KnowledgeBase knowledgeBaseForMethod, ITestResult result) {
    List<Field> matchingFields = findFieldsToInjectKnowledgeBaseInto(realTestClass);
    for (Field matchingField : matchingFields) {
      injectIntoField(matchingField, testInstance, knowledgeBaseForMethod, result);
    }
  }

  private void injectIntoField(Field matchingField, Object testInstance, KnowledgeBase knowledgeBaseForMethod, ITestResult result) {
    matchingField.setAccessible(true);
    try {
      matchingField.set(testInstance, knowledgeBaseForMethod);
    }
    catch (IllegalAccessException iae) {
      throw new SkipException("Error injecting KnowledgeBase into " + result.getTestClass().getName() + "." + matchingField.getName());
    }
  }

  private List<Field> findFieldsToInjectKnowledgeBaseInto(Class<?> realTestClass) {
    final Field[] declaredFields = realTestClass.getDeclaredFields();
    List<Field> matchingFields = new ArrayList<Field>();
    for (Field declaredField : declaredFields) {
      if (canAcceptKnowledgeBase(declaredField)) {
        matchingFields.add(declaredField);
      }
    }
    return matchingFields;
  }

  private boolean canAcceptKnowledgeBase(Field declaredField) {
    if (declaredField.getAnnotation(NoInjection.class) != null) {
      return false;
    }

    final Class<?> fieldType = declaredField.getType();
    if (fieldType.isAssignableFrom(KnowledgeBase.class)) {
      return true;
    }
    return false;
  }

  private KnowledgeBase completeKnowledgeBaseForMethod(KnowledgeBase knowledgeBaseForClass, Method realTestMethod, KnowledgeBase baseForClass) {
    return knowledgeBaseForClass;
  }

  private KnowledgeBase createNewKnowledgeBaseInstanceForClass(Class<?> realTestClass, KnowledgeBuilder knowledgeBuilder) {
    final CompileRules compileRulesAnnotation = realTestClass.getAnnotation(CompileRules.class);
    KnowledgeBase knowledgeBaseForClass = knowledgeBuilder.newKnowledgeBase();
    for (RuleSource ruleSource : compileRulesAnnotation.value()) {
      addToKnowledgeBase(ruleSource, knowledgeBuilder);
    }
    return knowledgeBaseForClass;
  }

  private void addToKnowledgeBase(RuleSource ruleSource, KnowledgeBuilder knowledgeBuilder) {
    boolean isForDirectory = isValueAvailable(ruleSource.directory());
    boolean isForIndividualFile = isValueAvailable(ruleSource.file());
    boolean conflict = isForDirectory && isForIndividualFile;
    if (conflict) {
      log.error("@" + RuleSource.class.getSimpleName() + " cannot be used to specify both directory '" + ruleSource.directory() + "'" +
                " and file '" + ruleSource.file() + "' at the same time.");
    }

    if (isForDirectory) {
      addAllFilesFromDirectory(knowledgeBuilder, new File(ruleSource.directory()));
    }
    else if (isForIndividualFile) {
      addIndividualFile(knowledgeBuilder, new File(ruleSource.file()));
    }
  }

  private void addAllFilesFromDirectory(KnowledgeBuilder knowledgeBuilder, File directory) {
    if ( ! directory.exists()) {
      throw new SkipException("Directory " + directory.getAbsolutePath() + " given by @" + RuleSource.class.getSimpleName() + " must exist (but does not).");
    }
    if ( ! directory.isDirectory() ) {
      throw new SkipException("Directory " + directory.getAbsolutePath() + " given by @" + RuleSource.class.getSimpleName() + " must denote a directory (but does not).");
    }

    Iterator<File> fileIterator = null;
    try {
      fileIterator = FileUtils.iterateFiles(directory, new String[] {"drl"}, false);
    }
    catch (Throwable t) {
      log.error("Caught " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }

    for (File file : IterableAdapter.makeFrom(fileIterator)) {
      addIndividualFile(knowledgeBuilder, file);
    }
  }

  private void addIndividualFile(KnowledgeBuilder knowledgeBuilder, File file) {
    if (!file.exists()) {
      handleFileDoesNotExist(file);
    }
    knowledgeBuilder.add(ResourceFactory.newFileResource(file), ResourceType.DRL);
    if (knowledgeBuilder.hasErrors()) {
      handleKnowledgeBuilderErrors(knowledgeBuilder);
    }
  }

  private void handleFileDoesNotExist(File file) {
    throw new RuntimeException(new IOException("File " + file.getAbsolutePath() + " not found, although given by @" + RuleSource.class.getSimpleName() + " annotation"));
  }

  private void handleKnowledgeBuilderErrors(KnowledgeBuilder knowledgeBuilder) {
    StringBuilder builder = new StringBuilder(1024);
    builder.append("Drools " + knowledgeBuilder.getClass().getSimpleName() + " error occurred:");
    for (KnowledgeBuilderError knowledgeBuilderError : knowledgeBuilder.getErrors()) {
      builder.append("Error in line(s) [");
      appendLinesTo(knowledgeBuilderError.getErrorLines(), builder);
      builder.append("]:");
      builder.append(knowledgeBuilderError.getMessage());
    }
    throw new RuntimeException(builder.toString());
  }

  private void appendLinesTo(int[] errorLines, StringBuilder appendToMe) {
    final int numberOfLines = errorLines.length;
    if (numberOfLines == 1) {
      appendToMe.append(errorLines[0]);
      return;
    }
    for (int i = 1; i < numberOfLines; i++) {
      appendToMe.append(",");
      appendToMe.append(errorLines[i]);
    }
  }

  private boolean isValueAvailable(String fileOrDirectoryName) {
    return (fileOrDirectoryName != null) && (!"".equals(fileOrDirectoryName));
  }

  private Class<?> obtainJavaTestClass(ITestResult result) {
    return result.getTestClass().getRealClass();
  }

  private Method obtainJavaTestMethod(ITestResult result) {
    return result.getMethod().getMethod();
  }

  @Override
  public void onTestSuccess(ITestResult result) {
  }

  @Override
  public void onTestFailure(ITestResult result) {
  }

  @Override
  public void onTestSkipped(ITestResult result) {
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
  }

  @Override
  public void onStart(ITestContext context) {
  }

  @Override
  public void onFinish(ITestContext context) {
  }
}
