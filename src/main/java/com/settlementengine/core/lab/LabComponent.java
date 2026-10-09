package com.settlementengine.core.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every lab bean carries this. Both the {@code demo} profile and
 * {@code settlement-engine.demo.enabled=true} must be present, otherwise the bean (and any
 * {@code /lab/**} mapping it would have contributed) does not exist.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Profile("demo")
@ConditionalOnProperty(name = "settlement-engine.demo.enabled", havingValue = "true")
public @interface LabComponent {

    @AliasFor(annotation = Component.class, attribute = "value")
    String value() default "";
}
