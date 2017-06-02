package com.orctom.laputa.service.shiro.mgt;

import com.orctom.laputa.service.model.RequestWrapper;
import com.orctom.laputa.service.model.ResponseWrapper;
import com.orctom.laputa.service.shiro.subject.LaputaDelegatingSubject;
import com.orctom.laputa.service.shiro.subject.LaputaSubjectContext;
import org.apache.shiro.mgt.DefaultSubjectFactory;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;

public class LaputaSubjectFactory extends DefaultSubjectFactory {

  public Subject createSubject(SubjectContext subjectContext) {
    if (!(subjectContext instanceof LaputaSubjectContext)) {
      return super.createSubject(subjectContext);
    }

    LaputaSubjectContext lsc = (LaputaSubjectContext) subjectContext;
    SecurityManager securityManager = lsc.resolveSecurityManager();
    Session session = lsc.resolveSession();
    boolean sessionEnabled = lsc.isSessionCreationEnabled();
    PrincipalCollection principals = lsc.resolvePrincipals();
    boolean authenticated = lsc.resolveAuthenticated();
    String host = lsc.resolveHost();
    RequestWrapper requestWrapper = lsc.getRequestWrapper();
    ResponseWrapper responseWrapper = lsc.getResponseWrapper();

    return new LaputaDelegatingSubject(
        principals,
        authenticated,
        host,
        session,
        sessionEnabled,
        requestWrapper,
        responseWrapper,
        securityManager
    );
  }
}
