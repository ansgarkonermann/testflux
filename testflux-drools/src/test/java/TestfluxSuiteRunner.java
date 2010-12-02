import org.testng.TestNG;

public class TestfluxSuiteRunner {

  public static void main(String[] args) {
    TestNG.main(new String[] {"-log", "3", suiteFileName()});
  }

  private static String suiteFileName() {
    final String file = TestfluxSuiteRunner.class.getResource("suite.xml").getFile();
    return file;
  }
}
