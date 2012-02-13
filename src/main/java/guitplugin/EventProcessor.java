package guitplugin;

import com.guit.client.apt.GwtEvent;
import com.guit.client.apt.GwtEvent.EventKind;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

@SupportedAnnotationTypes(value = {"com.guit.client.apt.GwtEvent"})
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class EventProcessor extends AbstractProcessor {

  private Elements elementsUtil;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    Filer filer = processingEnv.getFiler();

    this.elementsUtil = processingEnv.getElementUtils();

    for (TypeElement ann : annotations) {
      for (Element decl : roundEnv.getElementsAnnotatedWith(ann)) {
        if (decl.getKind().equals(ElementKind.CLASS)) {
          TypeElement classDeclaration = (TypeElement) decl;
          Collection<VariableElement> fields =
              ElementFilter.fieldsIn(elementsUtil.getAllMembers(classDeclaration));

          EventKind kind = decl.getAnnotation(GwtEvent.class).value();
          String packageName;
          switch (kind) {
            case DOM:
              packageName = "com.google.gwt.event.dom.client";
              break;
            case SHARED:
              packageName = "com.google.gwt.event.logical.shared";
              break;
            default:
              packageName =
                  elementsUtil.getPackageOf(classDeclaration).getQualifiedName().toString();
              break;
          }

          String simpleName = classDeclaration.getSimpleName().toString();
          String eventName = simpleName + "Event";
          String handlerName = simpleName + "Handler";

          PrintWriter writer;
          try {
            writer =
                new PrintWriter(filer.createSourceFile(packageName + "." + eventName).openWriter());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          writer.println("package " + packageName + ";");
          writer.println("import com.google.gwt.event.shared.GwtEvent;");
          writer.println("import com.google.gwt.event.shared.EventHandler;");
          Generated.printGeneratedImport(writer);
          writer.println();
          writer.println("import " + packageName + "." + eventName + "." + handlerName + ";");
          writer.println();
          Generated.printGenerated(writer, decl.getSimpleName().toString());
          writer.println("public class " + eventName + " extends GwtEvent<" + handlerName + "> {");

          StringBuilder constructorParameters = new StringBuilder();
          StringBuilder asignaments = new StringBuilder();
          StringBuilder toString = new StringBuilder();
          for (VariableElement f : fields) {
            String type = f.asType().toString();
            String name = f.getSimpleName().toString();

            if (toString.length() > 0) {
              toString.append(", ");
            }
            toString.append(name + "=" + "\" + " + name + " + \"");

            if (constructorParameters.length() > 0) {
              constructorParameters.append(", ");
            }

            asignaments.append("this." + name + " = " + name + ";");

            constructorParameters.append(type);
            constructorParameters.append(" ");
            constructorParameters.append(name);

            writer.println("  private final " + type + " " + name + ";");

            writer.println("  public " + type + (type.equals("boolean") ? " is" : " get")
                + name.substring(0, 1).toUpperCase() + name.substring(1) + "() {");
            writer.println("    return " + name + ";");
            writer.println("  }");
          }

          writer.println("  public String toString() {");
          writer.println("      if (!com.google.gwt.core.client.GWT.isScript()) {");
          writer.println("          return \"" + eventName + "[" + toString.toString() + "]\";");
          writer.println("      }");
          writer.println("      return super.toString();");
          writer.println("  }");

          writer.println("  public String toDebugString() {");
          writer.println("      return toString();");
          writer.println("  }");

          Generated.printGenerated(writer, decl.getSimpleName().toString());
          writer.println("  public static interface " + handlerName + " extends EventHandler {");
          writer.println("    void on" + simpleName + "(" + eventName + " event);");
          writer.println("  }");

          writer.println("  public static final Type<" + handlerName + "> TYPE = new Type<"
              + handlerName + ">();");

          writer.println("  public " + eventName + "(" + constructorParameters.toString() + ") {");
          if (asignaments.length() > 0) {
            writer.println("  " + asignaments.toString());
          }
          writer.println("  }");

          writer.println("  @Override");
          writer.println("  protected void dispatch(" + handlerName + " handler) {");
          writer.println("    handler.on" + simpleName + "(this);");
          writer.println("  }");

          writer.println("  @Override");
          writer.println("  public Type<" + handlerName + "> getAssociatedType() {");
          writer.println("    return TYPE;");
          writer.println("  }");
          writer.println("}");
          writer.close();
        }
      }
    }
    return false;
  }
}
