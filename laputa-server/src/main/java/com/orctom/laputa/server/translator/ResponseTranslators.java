package com.orctom.laputa.server.translator;

import com.orctom.laputa.server.config.ServiceConfig;
import com.orctom.laputa.server.model.Accepts;
import com.orctom.laputa.server.model.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ResponseTranslator registry
 * Created by hao on 11/25/15.
 */
public abstract class ResponseTranslators {

  private static final Map<String, ResponseTranslator> REGISTRY = new HashMap<>();

  static {
    registerTranslator(JsonResponseTranslator.TYPE.getExtension(), new JsonResponseTranslator());
    registerTranslator(JsonResponseTranslator.TYPE.getValue(), new JsonResponseTranslator());
    registerTranslator(XmlResponseTranslator.TYPE.getExtension(), new XmlResponseTranslator());
    registerTranslator(XmlResponseTranslator.TYPE.getValue(), new XmlResponseTranslator());
  }

  public static void registerTranslator(String type, ResponseTranslator encoder) {
    REGISTRY.put(type, encoder);
  }

  public static ResponseTranslator getTranslator(String uri, String accept) {
    String extension = getExtension(uri);
    ResponseTranslator translator = REGISTRY.get(extension);

    if (null != translator) {
      return translator;
    }

    if (null == accept || 0 == accept.trim().length()) {
      return getResponseTypeEncoder(MediaType.APPLICATION_JSON);
    }

    List<String> accepts = Accepts.sortAsList(accept);

    if (null == accepts) {
      return getResponseTypeEncoder(MediaType.APPLICATION_JSON);
    }
    for (String type : accepts) {
      ResponseTranslator encoder = REGISTRY.get(type);
      if (null != encoder) {
        return encoder;
      }
    }

    return getResponseTypeEncoder(MediaType.APPLICATION_JSON);
  }

  private static String getExtension(String uri) {
    int lastDotIndex = uri.lastIndexOf('.');
    String extension;
    if (lastDotIndex > 1) {
      extension = uri.substring(lastDotIndex);
    } else {
      try {
        extension = ServiceConfig.getInstance().getConfig().getString("default.extension");
      } catch (Exception e) {
        extension = JsonResponseTranslator.TYPE.getExtension();
      }
    }
    return extension;
  }

  private static ResponseTranslator getResponseTypeEncoder(MediaType mediaType) {
    return REGISTRY.get(mediaType.getValue());
  }
}
