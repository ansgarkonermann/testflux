package de.lightful.testflux.drools;

public class TestFluxException extends RuntimeException {

  public TestFluxException(String message) {
    super(message);
  }

  public TestFluxException(String message, Throwable cause) {
    super(message, cause);
  }

  public TestFluxException(Throwable cause) {
    super(cause);
  }

}
