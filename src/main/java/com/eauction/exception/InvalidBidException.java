package com.eauction.exception;

public class InvalidBidException extends RuntimeException {

    public InvalidBidException(String message) {
        super(message);
    }
}
