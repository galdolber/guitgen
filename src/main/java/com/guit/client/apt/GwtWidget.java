package com.guit.client.apt;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marks a class as widget.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface GwtWidget {
}
