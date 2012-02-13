package guitplugin;

import java.io.PrintWriter;
import java.util.Date;

public class Generated {

  public static void printGeneratedImport(PrintWriter writer) {
    // writer.println("import javax.annotation.Generated;");
  }

  public static void printGenerated(PrintWriter writer, String value) {
    writer.println("// @Generated(value = \"" + value + "\", date=\"" + new Date().toString()
        + "\")");
  }
}
