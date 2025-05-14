package org.ebean.monitor.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class GzipUtil {

  public static String decompress(byte[] compressed) {

    try {
      final StringBuilder outStr = new StringBuilder();
      final GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
      final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8));

      String line;
      while ((line = bufferedReader.readLine()) != null) {
        outStr.append(line);
      }
      return outStr.toString();

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
