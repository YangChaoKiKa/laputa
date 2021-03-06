package com.orctom.laputa.service.shiro.filter;

import com.orctom.laputa.service.filter.Filter;
import com.orctom.laputa.service.filter.FilterChain;
import com.orctom.laputa.service.model.RequestWrapper;
import com.orctom.laputa.service.model.ResponseWrapper;
import com.orctom.laputa.service.shiro.mgt.FilterChainResolver;
import com.orctom.laputa.service.shiro.subject.LaputaSubject;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.orctom.laputa.service.shiro.ShiroContext.getFilterChainResolver;
import static com.orctom.laputa.service.shiro.ShiroContext.getSecurityManager;

@Component
public class ShiroFilter implements Filter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ShiroFilter.class);

  @Override
  public int getOrder() {
    return 0;
  }

  @Override
  public void doFilter(RequestWrapper requestWrapper, ResponseWrapper responseWrapper, FilterChain filterChain) {
    final Subject subject = createSubject(requestWrapper, responseWrapper);
    subject.execute(() -> {
      updateSessionLastAccessTime(requestWrapper, responseWrapper);
      executeChain(requestWrapper, responseWrapper, filterChain);
    });
  }

  private LaputaSubject createSubject(RequestWrapper requestWrapper, ResponseWrapper responseWrapper) {
    return new LaputaSubject.Builder(getSecurityManager(), requestWrapper, responseWrapper).buildSubject();
  }

  private void updateSessionLastAccessTime(RequestWrapper requestWrapper, ResponseWrapper responseWrapper) {
    Subject subject = SecurityUtils.getSubject();
    //Subject should never _ever_ be null, but just in case:
    if (subject != null) {
      Session session = subject.getSession(false);
      if (session != null) {
        try {
          session.touch();
        } catch (Throwable t) {
          LOGGER.error("session.touch() method invocation has failed.  Unable to update" +
              "the corresponding session's last access time based on the incoming request.", t);
        }
      }
    }
  }

  private void executeChain(RequestWrapper requestWrapper, ResponseWrapper responseWrapper, FilterChain filterChain) {
    FilterChain chain = getExecutionChain(requestWrapper, responseWrapper, filterChain);
    chain.doFilter(requestWrapper, responseWrapper);
    setUsernameToResponse(responseWrapper);
  }

  private void setUsernameToResponse(ResponseWrapper responseWrapper) {
    Object username = SecurityUtils.getSubject().getPrincipal();
    if (Objects.nonNull(username)) {
      responseWrapper.setData("username", username);
    }
  }

  private FilterChain getExecutionChain(RequestWrapper requestWrapper,
                                        ResponseWrapper responseWrapper,
                                        FilterChain filterChain) {
    FilterChainResolver resolver = getFilterChainResolver();
    return resolver.getChain(requestWrapper, responseWrapper, filterChain);
  }
}
