package com.orctom.laputa.service.exception;

import com.orctom.laputa.exception.FastException;

public class FileUploadException extends FastException {

  public FileUploadException(String message) {
    super(message);
  }

  public FileUploadException(String message, Throwable cause) {
    super(message, cause);
  }
}
