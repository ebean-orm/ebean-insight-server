package org.ebean.monitor.web;

import java.time.Instant;

public record TimeEvents(Instant time, long value) {
}
