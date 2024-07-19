package com.aivech.atomikvk.shaderc;

public class ShaderException extends RuntimeException {
    public ShaderException(String message, Throwable cause) {
        super(message, cause);
    }

    public ShaderException(Throwable cause) {
        super(cause);
    }

    public ShaderException(String message) {
        super(message);
    }
}
