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

import org.drools.KnowledgeBase;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import static org.fest.assertions.Assertions.assertThat;

@RulesBaseDirectory("src/test/rules")
@CompileRules(
    {
        @RuleSource(directory = "from-directory-one"),
        @RuleSource(directory = "from-directory-two"),
        @RuleSource(file = "another-directory/rule-file-one.drl"),
        @RuleSource(file = "another-directory/rule-file-two.drl")
    }
)
@Test
@Listeners(DroolsRuleTestListener.class)
public class RuleCompilerDataProviderTest {

  private KnowledgeBase knowledgeBase;

  public void test_knowledge_base_gets_injected_by_listener() {
    assertThat(knowledgeBase).as("Injected KnowledgeBase").isNotNull();
  }

}
