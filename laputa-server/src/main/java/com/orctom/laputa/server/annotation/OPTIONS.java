package com.orctom.laputa.server.annotation;

import javax.ws.rs.HttpMethod;
import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@HttpMethod(HttpMethod.OPTIONS)
@Documented
public @interface OPTIONS {
}
