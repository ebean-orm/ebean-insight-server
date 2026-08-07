@JStacheConfig(formatter = SpecFormatter.class)
@JStachePath(prefix = "ui/", suffix = ".mustache")
@JStacheFormatterTypes(types = {Instant.class, Duration.class, Timestamp.class,
  LocalDate.class, BigDecimal.class, ZonedDateTime.class})
package org.ebean.monitor.web.view;

import io.jstach.jstache.JStacheConfig;
import io.jstach.jstache.JStacheFormatterTypes;
import io.jstach.jstache.JStachePath;
import io.jstach.jstachio.formatters.SpecFormatter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
