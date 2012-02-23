package guitplugin;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.StandardLocation;

public class FilerUtil {

  private Filer filer;
  private Elements elementsUtil;

  public FilerUtil(ProcessingEnvironment env) {
    this.filer = env.getFiler();
    this.elementsUtil = env.getElementUtils();
  }

  public String readSource(TypeElement type) {
    String pack = elementsUtil.getPackageOf(type).getQualifiedName().toString();
    for (StandardLocation location : StandardLocation.values()) {
      try {
        return filer.getResource(location, pack, type.getSimpleName() + ".java").getCharContent(
            true).toString();
      } catch (Exception e) {
      }
    }
    throw new RuntimeException("Error locating " + type.getQualifiedName().toString());
  }
}
