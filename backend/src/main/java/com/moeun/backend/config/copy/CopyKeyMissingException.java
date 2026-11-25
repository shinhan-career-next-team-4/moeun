package com.moeun.backend.config.copy;

/**
 * 문구표에 키가 없거나 값이 비어 있을 때 던진다(CP-05). 빈 문구로 조용히 넘어가지 않기 위해서다.
 */
public class CopyKeyMissingException extends RuntimeException {

    public CopyKeyMissingException(String message) {
        super(message);
    }
}
