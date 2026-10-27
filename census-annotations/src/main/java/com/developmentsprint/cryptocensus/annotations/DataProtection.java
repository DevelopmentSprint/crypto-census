package com.developmentsprint.cryptocensus.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares protection requirements for a method's {@link CryptoFlow}.
 *
 * <p>Version 1 requires {@code @CryptoFlow} on the same method. These values are business
 * declarations, not observations or guarantees. Invalid declarations are diagnosed by the observing
 * agent; annotation use alone performs no validation and changes no application behavior.
 *
 * <p>A positive lifetime is a minimum requirement from data creation or signing, not an expiry
 * calculated from the current date. Valid declared lifetimes are 1 through 1000 years. Zero does
 * not mean public or safe; zero and negative values other than {@link #UNDECLARED} are invalid.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@CensusAnnotationApi(1)
public @interface DataProtection {
  /** Sentinel for a lifetime that has not been declared. */
  int UNDECLARED = -1;

  /**
   * An organization-defined classification identifier.
   *
   * @return an ID matching {@code [a-z0-9][a-z0-9._-]{0,63}}, or empty when undeclared
   */
  String classification() default "";

  /**
   * Minimum required confidentiality lifetime from data creation.
   *
   * @return a lifetime in 1–1000 years, or {@link #UNDECLARED}
   */
  int confidentialityYears() default UNDECLARED;

  /**
   * Minimum required authenticity lifetime from signing.
   *
   * @return a lifetime in 1–1000 years, or {@link #UNDECLARED}
   */
  int authenticityYears() default UNDECLARED;
}
