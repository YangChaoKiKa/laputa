package com.orctom.laputa.service.shiro.config;

import com.orctom.laputa.service.shiro.filter.DefaultFilter;
import com.orctom.laputa.service.shiro.filter.Filter;
import com.orctom.laputa.service.shiro.mgt.LaputaSecurityManager;
import org.apache.shiro.config.Ini;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.SecurityManager;

import java.util.Map;

public class LaputaIniSecurityManagerFactory extends IniSecurityManagerFactory {

  public LaputaIniSecurityManagerFactory() {
    super();
  }

  public LaputaIniSecurityManagerFactory(Ini config) {
    super(config);
  }

  @Override
  protected SecurityManager createDefaultInstance() {
    return new LaputaSecurityManager();
  }

  @SuppressWarnings({"unchecked"})
  @Override
  protected Map<String, ?> createDefaults(Ini ini, Ini.Section mainSection) {
    Map defaults = super.createDefaults(ini, mainSection);
    //add the default filters:
    Map<String, Filter> defaultFilters = DefaultFilter.createInstanceMap();
    defaults.putAll(defaultFilters);
    return defaults;
  }
}
