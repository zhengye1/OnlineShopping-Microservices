package com.onlineshopping.product.snowflake;

public class ClockMovedBackwardsException extends RuntimeException {
    public ClockMovedBackwardsException(String message){
        super(message);
    }
}
