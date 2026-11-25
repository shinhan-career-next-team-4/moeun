package com.moeun.backend.refdata.application;

/** 기준 데이터 파일을 적재할 수 없을 때. 메시지에는 줄 번호와 이유를 담는다. */
public class ReferenceDataLoadException extends RuntimeException {

    public ReferenceDataLoadException(String message) {
        super(message);
    }

    public ReferenceDataLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    static ReferenceDataLoadException atLine(int lineNumber, String reason) {
        return new ReferenceDataLoadException("line " + lineNumber + ": " + reason);
    }
}
