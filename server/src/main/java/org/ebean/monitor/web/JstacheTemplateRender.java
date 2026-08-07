package org.ebean.monitor.web;

import io.avaje.inject.Component;
import io.avaje.jex.htmx.TemplateRender;
import io.jstach.jstachio.JStachio;

@Component
public class JstacheTemplateRender implements TemplateRender {

    @Override
    public String render(Object viewModel) {
      return JStachio.render(viewModel);
    }
}
