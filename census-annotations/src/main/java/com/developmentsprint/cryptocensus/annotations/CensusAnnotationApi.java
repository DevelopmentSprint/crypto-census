package com.developmentsprint.cryptocensus.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifies the metadata contract implemented by a Census annotation type.
 *
 * <p>This infrastructure marker is supplied on the annotation types themselves. Application methods
 * do not need to repeat it. Observers must diagnose unsupported versions rather than assume they
 * understand the declaration.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface CensusAnnotationApi {
  /**
   * The metadata contract version.
   *
   * @return the version, currently {@code 1}
   */
  int value();
}
