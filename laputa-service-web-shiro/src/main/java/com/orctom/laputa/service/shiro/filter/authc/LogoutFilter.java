package com.orctom.laputa.service.shiro.filter.authc;

import com.orctom.laputa.service.model.RequestWrapper;
import com.orctom.laputa.service.model.ResponseWrapper;
import com.orctom.laputa.service.shiro.filter.AbstractFilter;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.session.SessionException;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogoutFilter extends AbstractFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(LogoutFilter.class);

  private String redirectUrl = "/";

  public String getRedirectUrl() {
    return redirectUrl;
  }

  public void setRedirectUrl(String redirectUrl) {
    this.redirectUrl = redirectUrl;
  }

  @Override
  protected boolean preHandle(RequestWrapper requestWrapper, ResponseWrapper responseWrapper) {
    Subject subject = getSubject(requestWrapper);
    String redirectUrl = getRedirectUrl();
    try {
      subject.logout();
    } catch (SessionException ise) {
      LOGGER.debug("Encountered session exception during logout.  This can generally safely be ignored.", ise);
    }
    issueRedirect(requestWrapper, responseWrapper, redirectUrl);
    return false;
  }

  protected Subject getSubject(RequestWrapper requestWrapper) {
    return SecurityUtils.getSubject();
  }

  protected void issueRedirect(RequestWrapper requestWrapper, ResponseWrapper responseWrapper, String redirectUrl) {
    responseWrapper.setRedirectTo(redirectUrl);
  }
}
