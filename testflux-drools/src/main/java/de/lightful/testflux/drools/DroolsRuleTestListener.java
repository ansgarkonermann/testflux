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

import com.google.inject.Inject;
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

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DroolsRuleTestListener implements ITestListener {

  private static Logger log = Logger.getLogger(DroolsRuleTestListener.class);

  private ThreadLocal<ITestResult> testResult = new ThreadLocal<ITestResult>();
  private static final String TESTFLUX_CONFIG_FILE = "/testflux.properties";

  public DroolsRuleTestListener() {
    log.debug("Creating new instance of " + this.getClass().getName() + ".");
  }

  @Override
  public void onTestStart(ITestResult result) {
    testResult.set(result);
    final Class<?> realTestClass = obtainJavaTestClass(result);
    final Method realTestMethod = obtainJavaTestMethod(result);

    KnowledgeBuilder knowledgeBuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
    KnowledgeBase knowledgeBaseForClass = createNewKnowledgeBaseInstanceForClass(realTestClass, knowledgeBuilder);
    KnowledgeBase knowledgeBaseForMethod = completeKnowledgeBaseForMethod(knowledgeBaseForClass, realTestMethod, knowledgeBaseForClass);

    knowledgeBaseForMethod.addKnowledgePackages(knowledgeBuilder.getKnowledgePackages());

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
      throw new TestFluxException("Error injecting KnowledgeBase into " + result.getTestClass().getName() + "." + matchingField.getName());
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
    if ( declaredField.getAnnotation(Inject.class) == null ) {
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
    final RulesBaseDirectory rulesBaseDirectory = realTestClass.getAnnotation(RulesBaseDirectory.class);
    KnowledgeBase knowledgeBaseForClass = knowledgeBuilder.newKnowledgeBase();
    for (RuleSource ruleSource : compileRulesAnnotation.value()) {
      addToKnowledgeBase(rulesBaseDirectory, ruleSource, knowledgeBuilder);
    }
    return knowledgeBaseForClass;
  }

  private void addToKnowledgeBase(RulesBaseDirectory rulesBaseDirectory, RuleSource ruleSource, KnowledgeBuilder knowledgeBuilder) {
    boolean isForDirectory = isValueAvailable(ruleSource.directory());
    boolean isForIndividualFile = isValueAvailable(ruleSource.file());
    boolean conflict = isForDirectory && isForIndividualFile;
    if (conflict) {
      throw new TestFluxException("@" + RuleSource.class.getSimpleName() + " cannot be used to specify both directory '" + ruleSource.directory() + "'" +
                                  " and file '" + ruleSource.file() + "' at the same time.");
    }

    if (isForDirectory) {
      addAllFilesFromDirectory(knowledgeBuilder, ruleSource.directory(), rulesBaseDirectory);
    }
    else if (isForIndividualFile) {
      addIndividualFileFromBaseDirectory(knowledgeBuilder, ruleSource.file(), rulesBaseDirectory);
    }
  }

  private void addAllFilesFromDirectory(KnowledgeBuilder knowledgeBuilder, String directoryName, RulesBaseDirectory ruleBaseDirectory) {
    String baseDirectory = determineBaseDirectory(ruleBaseDirectory);
    File directory = fileFromBaseDirectory(directoryName, baseDirectory);

    if (!directory.exists()) {
      throw new TestFluxException("Directory " + directory.getAbsolutePath() + " given by @" + RuleSource.class.getSimpleName() + " must exist (but does not).");
    }
    if (!directory.isDirectory()) {
      throw new TestFluxException("Directory " + directory.getAbsolutePath() + " given by @" + RuleSource.class.getSimpleName() + " must denote a directory (but does not).");
    }

    Iterator<File> fileIterator = null;
    try {
      fileIterator = FileUtils.iterateFiles(directory, new String[] {"drl"}, false);
    }
    catch (Throwable t) {
      throw new TestFluxException("Caught " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    for (File file : IterableAdapter.makeFrom(fileIterator)) {
      addIndividualFile(knowledgeBuilder, file);
    }
  }

  private String determineBaseDirectory(RulesBaseDirectory ruleBaseDirectory) {
    String baseDirectory = null;
    if (ruleBaseDirectory != null) {
      baseDirectory = ruleBaseDirectory.value();
    }
    else {
      baseDirectory = "src" + File.separator + "main" + File.separator + "rules";
    }
    final String rulesRootDirectory = testResult.get().getTestClass().getXmlTest().getSuite().getParameter("rulesRootDirectory");
    return rulesRootDirectory + File.separator + baseDirectory;
  }

  private void addIndividualFileFromBaseDirectory(KnowledgeBuilder knowledgeBuilder, String filename, RulesBaseDirectory rulesBaseDirectory) {
    String baseDirectory = determineBaseDirectory(rulesBaseDirectory);
    File file = fileFromBaseDirectory(filename, baseDirectory);
    addIndividualFile(knowledgeBuilder, file);
  }

  private void addIndividualFile(KnowledgeBuilder knowledgeBuilder, File file) {
    ensureFileExists(file);
    ensureIsRegularFile(file);
    addFileToKnowledgeBase(knowledgeBuilder, file);
    handleKnowledgeBuilderErrors(knowledgeBuilder);
  }

  private void handleKnowledgeBuilderErrors(KnowledgeBuilder knowledgeBuilder) {
    if (knowledgeBuilder.hasErrors()) {
      StringBuilder builder = new StringBuilder(1024);
      builder.append("Drools " + knowledgeBuilder.getClass().getSimpleName() + " error occurred:");
      for (KnowledgeBuilderError knowledgeBuilderError : knowledgeBuilder.getErrors()) {
        builder.append("Error in line(s) [");
        appendLinesTo(knowledgeBuilderError.getErrorLines(), builder);
        builder.append("]:");
        builder.append(knowledgeBuilderError.getMessage());
      }
      throw new TestFluxException(builder.toString());
    }
  }

  private void ensureIsRegularFile(File file) {
    if (!file.isFile()) {
      throw new TestFluxException("File " + file.getAbsolutePath() + " given by @" + RuleSource.class.getSimpleName() + " must denote a regular file (but does not).");
    }
  }

  private void ensureFileExists(File file) {
    if (!file.exists()) {
      throw new TestFluxException("File " + file.getAbsolutePath() + " not found, although given by @" + RuleSource.class.getSimpleName() + " annotation");
    }
  }

  private File fileFromBaseDirectory(String filename, String baseDirectory) {
    return new File(baseDirectory + File.separator + filename);
  }

  private void addFileToKnowledgeBase(KnowledgeBuilder knowledgeBuilder, File file) {
    try {
      knowledgeBuilder.add(ResourceFactory.newFileResource(file), ResourceType.DRL);
    }
    catch (Throwable t) {
      throw new TestFluxException("Exception occurred while adding file " + file.getAbsolutePath() + " to knowledge base: " + t.getMessage());
    }
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
