package guitplugin.guitview;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class T {

  /**
   * @param args
   */
  public static void main(String[] args) {
    Pattern pattern = Pattern.compile("^import (([\\w&&^[;]])*);$");
    Matcher matcher = pattern.matcher("import guitplugin.guitview;" +
    		"import java.util.regex.Matcher;" +
    		"java.util.regex.Pattern;");
    while (matcher.find()) {
      System.out.print("Start index: " + matcher.start());
      System.out.print(" End index: " + matcher.end() + " ");
      System.out.println(matcher.group(1));
    }
  }
}
