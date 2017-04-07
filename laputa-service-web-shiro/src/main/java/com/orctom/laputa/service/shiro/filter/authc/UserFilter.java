package com.orctom.laputa.service.shiro.filter.authc;

import com.orctom.laputa.service.model.Context;
import com.orctom.laputa.service.model.RequestWrapper;
import com.orctom.laputa.service.shiro.filter.AccessControlFilter;
import org.apache.shiro.subject.Subject;

public class UserFilter extends AccessControlFilter {

  @Override
  protected boolean isAccessAllowed(RequestWrapper requestWrapper, Context context, Object mappedValue) {
    if (isLoginRequest(requestWrapper)) {
      return true;
    } else {
      Subject subject = getSubject(requestWrapper);
      // If principal is not null, then the user is known and should be allowed access.
      return subject.getPrincipal() != null;
    }
  }

  @Override
  protected void checkAccess(RequestWrapper requestWrapper, Context context, Object mappedValue) {
    saveRequestAndRedirectToLogin(requestWrapper, context);
  }
}
