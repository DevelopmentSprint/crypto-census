package com.developmentsprint.cryptocensus.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a stable business-flow identity for observations made while a method executes.
 *
 * <p>The ID is scoped by application identity, independent of method names or source lines. IDs
 * must match {@code [a-z0-9][a-z0-9._-]{0,63}}; the observing agent diagnoses invalid declarations.
 * Multiple methods may intentionally share an ID, but conflicting declarations require review.
 *
 * <p>Version 1 supports concrete executable methods. Interface declarations and overridden methods
 * do not confer annotations on implementations or overrides. The agent observes synchronous dynamic
 * execution scope; this is neither data-flow tracing nor automatic asynchronous propagation. With
 * no agent attached, this annotation has no operational effect.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@CensusAnnotationApi(1)
public @interface CryptoFlow {
  /**
   * The stable lowercase ASCII flow ID.
   *
   * @return an ID matching {@code [a-z0-9][a-z0-9._-]{0,63}}
   */
  String value();

  /**
   * The declared purpose, which is context rather than proof of protection.
   *
   * @return the purpose or {@link CryptoPurpose#UNSPECIFIED} when undeclared
   */
  CryptoPurpose purpose() default CryptoPurpose.UNSPECIFIED;
}
