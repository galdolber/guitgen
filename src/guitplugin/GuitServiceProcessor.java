package guitplugin;

import com.guit.client.apt.Cache;
import com.guit.client.apt.GuitService;
import com.guit.client.apt.Ignore;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

@SupportedAnnotationTypes(value = {"com.guit.client.apt.GuitService"})
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class GuitServiceProcessor extends AbstractProcessor {
  private Elements elementsUtil;
  private Filer filer;
  private Types typeUtils;

  @SuppressWarnings("unchecked")
  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    ProcessingEnvironment env = processingEnv;
    this.elementsUtil = env.getElementUtils();
    this.filer = env.getFiler();
    this.typeUtils = env.getTypeUtils();

    for (TypeElement ann : annotations) {
      for (Element e : roundEnv.getElementsAnnotatedWith(ann)) {
        if (e.getKind().equals(ElementKind.INTERFACE)) {
          TypeElement d = (TypeElement) e;
          Collection<? extends AnnotationMirror> mirrors = d.getAnnotationMirrors();
          List<AnnotationValue> interfaces = new ArrayList<AnnotationValue>();
          for (AnnotationMirror m : mirrors) {
            String name =
                ((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().toString();
            if (name.equals(GuitService.class.getCanonicalName())) {
              for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : m
                  .getElementValues().entrySet()) {
                interfaces = (List<AnnotationValue>) entry.getValue().getValue();
              }
            }
          }

          ArrayList<String> implement = new ArrayList<String>();
          for (AnnotationValue a : interfaces) {
            implement.add((String) a.getValue());
          }
          generateRpcService(d, implement);
        }
      }
    }

    return false;
  }

  public void generateRpcService(TypeElement d, ArrayList<String> implement) {
    PackageElement pack = elementsUtil.getPackageOf(d);
    String dpackage = pack.getQualifiedName().toString();
    if (!pack.getSimpleName().toString().equals("server")) {
      processingEnv.getMessager().printMessage(Kind.ERROR,
          "All services must be on a 'server' package", d);
      return;
    }

    String clientPkg = dpackage.substring(0, dpackage.lastIndexOf(".")) + ".client";
    String actionPkg = clientPkg + ".action";
    String hanlderPkg = dpackage + ".handler";

    List<ExecutableElement> methods = ElementFilter.methodsIn(elementsUtil.getAllMembers(d));

    String serviceName = d.getSimpleName().toString();

    HashSet<String> createdMethods = new HashSet<String>();

    // Generate service async
    String asyncServiceName = serviceName + "Async";
    PrintWriter asyncServiceWriter = getPrintWriter(clientPkg + "." + asyncServiceName);
    asyncServiceWriter.println("package " + clientPkg + ";");
    asyncServiceWriter.println();
    asyncServiceWriter.println("import com.google.inject.Inject;");
    asyncServiceWriter.println();
    asyncServiceWriter.println("import com.guit.client.async.AbstractAsyncCallback;");
    asyncServiceWriter.println("import com.guit.client.command.Async;");
    asyncServiceWriter.println("import com.guit.client.command.AsyncMethod;");
    asyncServiceWriter.println("import com.guit.client.command.CommandService;");
    Generated.printGeneratedImport(asyncServiceWriter);
    asyncServiceWriter.println();
    asyncServiceWriter.println("/**");
    asyncServiceWriter.println(" * Async access to " + serviceName);
    asyncServiceWriter.println(" */");
    Generated.printGenerated(asyncServiceWriter, serviceName);
    asyncServiceWriter.print("public class " + asyncServiceName);
    if (implement.size() > 0) {
      asyncServiceWriter.print(" implements ");
      boolean first = true;
      for (String i : implement) {
        if (first) {
          first = false;
        } else {
          asyncServiceWriter.print(", ");
        }
        asyncServiceWriter.print(i);
      }
    }
    asyncServiceWriter.println(" {");
    asyncServiceWriter.println();
    asyncServiceWriter.println("  private final CommandService commandService;");
    asyncServiceWriter.println();
    asyncServiceWriter.println("  @Inject");
    asyncServiceWriter
        .println("  public " + asyncServiceName + "(CommandService commandService) {");
    asyncServiceWriter.println("    this.commandService = commandService;");
    asyncServiceWriter.println("  }");
    asyncServiceWriter.println();

    for (ExecutableElement m : methods) {
      if (!((TypeElement) m.getEnclosingElement()).getQualifiedName().toString().equals(
          "java.lang.Object")
          && m.getAnnotation(Ignore.class) == null) {

        String methodName = m.getSimpleName().toString();
        methodName = methodName.substring(0, 1).toUpperCase() + methodName.substring(1);

        String postfix = "";
        int count = 0;
        while (createdMethods.contains(methodName + postfix)) {
          postfix = String.valueOf(count);
          count++;
        }
        createdMethods.add(methodName);

        HashMap<String, String> actionFields = new HashMap<String, String>();

        String handlerClass =
            dpackage + ".handler." + serviceName + methodName + "Handler" + postfix;
        String actionClass = actionPkg + "." + serviceName + methodName + "Action" + postfix;
        String responseClass = actionPkg + "." + serviceName + methodName + "Response" + postfix;

        StringBuilder gettersList = new StringBuilder();

        List<? extends VariableElement> parameters = m.getParameters();
        for (VariableElement p : parameters) {
          String pName = p.getSimpleName().toString();
          actionFields.put(pName, p.asType().toString());

          if (gettersList.length() > 0) {
            gettersList.append(", ");
          }
          pName = pName.substring(0, 1).toUpperCase() + pName.substring(1);
          gettersList.append("action.get" + pName + "()");
        }

        TypeMirror returnType = m.getReturnType();
        boolean doesntReturnsVoid =
            returnType != null && !returnType.getKind().equals(TypeKind.VOID);
        String responseFields = null;
        if (doesntReturnsVoid) {
          responseFields = returnType.toString();
        }

        generateAction(serviceName, actionFields, responseFields, handlerClass, methodName,
            actionPkg, m.getAnnotation(Cache.class) != null, postfix);

        try {
          PrintWriter writer = getPrintWriter(handlerClass);

          String service = d.getQualifiedName().toString();

          writer.println("package " + hanlderPkg + ";");
          writer.println();

          writer.println("import com.google.inject.Inject;");
          writer.println();
          writer.println("import com.guit.client.command.action.CommandException;");
          writer.println("import com.guit.client.command.action.Handler;");
          writer.println("import " + service + ";");
          Generated.printGeneratedImport(writer);
          writer.println();
          Generated.printGenerated(writer, serviceName);
          writer.println("public class " + serviceName + methodName + "Handler" + postfix
              + " implements Handler<" + actionClass + ", " + responseClass + "> {");

          // Print all preprocesors injections
          ArrayList<String> preprocessors = new ArrayList<String>();
          Collection<? extends AnnotationMirror> annotations = m.getAnnotationMirrors();
          for (AnnotationMirror mirror : annotations) {
            TypeElement a = ((TypeElement) mirror.getAnnotationType().asElement());
            Collection<? extends AnnotationMirror> aAnnotations = a.getAnnotationMirrors();
            for (AnnotationMirror aAnn : aAnnotations) {
              if (((TypeElement) aAnn.getAnnotationType().asElement()).getQualifiedName()
                  .toString().equals("com.guit.server.command.PreprocesorAnnotation")) {
                for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : aAnn
                    .getElementValues().entrySet()) {
                  Object value = entry.getValue().getValue();
                  TypeElement preprocesorType =
                      (TypeElement) typeUtils.asElement(((TypeMirror) value));
                  writer.println();
                  writer.println("  @Inject");
                  String qualifiedName = preprocesorType.getQualifiedName().toString();
                  String fieldName = qualifiedName.replaceAll("[.]", "_");
                  writer.println("  " + qualifiedName + " " + fieldName + ";");

                  String annotation =
                      ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName()
                          .toString();
                  String annFieldName = fieldName + "Annotation";
                  writer.append("  private static " + annotation + " " + annFieldName + ";");

                  writer.println("  static {");
                  writer.println("    try {");
                  writer.print("      " + annFieldName + " = " + service
                      + ".class.getDeclaredMethod(\"" + m.getSimpleName() + "\", new Class[]{");

                  // Method parameters
                  boolean first = true;
                  for (VariableElement p : m.getParameters()) {
                    if (!first) {
                      writer.print(",");
                    }

                    writer.print(((TypeElement) p).getQualifiedName().toString() + ".class");

                    first = false;
                  }

                  writer.println("}).getAnnotation(" + annotation + ".class);");

                  writer.println("    } catch(" + NoSuchMethodException.class.getCanonicalName()
                      + " e) {");
                  writer.println("      throw new " + RuntimeException.class.getCanonicalName()
                      + "(e);");
                  writer.println("    }");
                  writer.println("  }");

                  preprocessors.add(fieldName);
                }
              }
            }
          }

          writer.println();
          writer.println("  @Inject");
          writer.println("  " + serviceName + " service;");
          writer.println();
          writer.println("  @Override");
          writer.println("  public " + responseClass + " handle(" + actionClass
              + " action) throws CommandException {");

          // Preprocesors
          for (String p : preprocessors) {
            writer.println("    " + p + ".run(" + p + "Annotation);");
          }

          if (doesntReturnsVoid) {
            writer.println("    return new " + responseClass + "(service." + m.getSimpleName()
                + "(" + gettersList + "));");
          } else {
            writer.println("    service." + m.getSimpleName() + "(" + gettersList + ");");
            writer.println("    return new " + responseClass + "();");
          }

          writer.println("  }");
          writer.println("}");
          writer.close();

          methodName = methodName.substring(0, 1).toUpperCase() + methodName.substring(1);

          String returnClass;

          TypeMirror rt = m.getReturnType();
          if (rt.getKind().isPrimitive()) {
            returnClass = rt.toString();
            returnClass =
                "java.lang." + returnClass.substring(0, 1).toUpperCase() + returnClass.substring(1);
          } else if (rt.getKind().equals(TypeKind.VOID)) {
            returnClass = "java.lang.Void";
          } else {
            returnClass = rt.toString();
          }

          asyncServiceWriter.println();
          asyncServiceWriter.println("  /**");
          asyncServiceWriter.println("   * Async call to " + serviceName + "." + methodName);
          asyncServiceWriter.println("   */");
          asyncServiceWriter.print("  public AsyncMethod<");
          asyncServiceWriter.print(returnClass);
          asyncServiceWriter.print(">");
          asyncServiceWriter.print(" ");
          asyncServiceWriter.print(m.getSimpleName());
          asyncServiceWriter.print("(");

          boolean first = true;
          for (VariableElement p : m.getParameters()) {
            if (!first) {
              asyncServiceWriter.print(", ");
            } else {
              first = false;
            }

            asyncServiceWriter.print(p.asType().toString());
            asyncServiceWriter.print(" ");
            asyncServiceWriter.print(p.getSimpleName());
          }
          asyncServiceWriter.println(") {");

          // Instantiate action and set values
          asyncServiceWriter.println("    final " + actionClass + " action = new " + actionClass
              + "();");
          for (VariableElement p : m.getParameters()) {
            String parameterName = p.getSimpleName().toString();
            asyncServiceWriter.println("    action.set"
                + parameterName.substring(0, 1).toUpperCase() + parameterName.substring(1) + "("
                + parameterName + ");");
          }

          // Async method
          asyncServiceWriter.println("    return new AsyncMethod<" + returnClass + ">() {");
          asyncServiceWriter.println();
          asyncServiceWriter.println("      public void fire(final Async<" + returnClass
              + "> async) {");
          asyncServiceWriter
              .println("        commandService.execute(action, new AbstractAsyncCallback<");
          asyncServiceWriter.println("            " + responseClass + ">() {");
          asyncServiceWriter.println("          public void success(" + responseClass
              + " response) {");
          asyncServiceWriter.println("            async.success("
              + (returnClass.equals(Void.class.getCanonicalName()) ? "null"
                  : "response.getResult()") + ");");
          asyncServiceWriter.println("          }");
          asyncServiceWriter.println();
          asyncServiceWriter.println("          public void failure("
              + Throwable.class.getCanonicalName() + " ex) {");
          asyncServiceWriter.println("            async.failure(ex);");
          asyncServiceWriter.println("          }");
          asyncServiceWriter.println("        });");
          asyncServiceWriter.println("      }");
          asyncServiceWriter.println("    };");
          asyncServiceWriter.println("  }");

        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    asyncServiceWriter.println("}");
    asyncServiceWriter.close();
  }

  public void generateAction(String serviceName, HashMap<String, String> actionFields,
      String responseField, String handlerClass, String simpleName, String packageName,
      boolean cacheable, String postfix) {

    String actionName = serviceName + simpleName + "Action" + postfix;
    String responseName = serviceName + simpleName + "Response" + postfix;

    PrintWriter writer = getPrintWriter(packageName + "." + actionName);
    writer.println("package " + packageName + ";");

    if (cacheable) {
      writer.println("import com.guit.client.command.action.Cacheable;");
    }

    writer.println("import com.guit.client.command.HashCodeBuilder;");
    writer.println("import com.guit.client.command.action.Action;");
    writer.println("import com.guit.client.command.action.ActionHandler;");

    writer.println("import " + packageName + "." + responseName + ";");
    Generated.printGeneratedImport(writer);

    Generated.printGenerated(writer, simpleName);
    writer.println("@ActionHandler(\"" + handlerClass + "\")");
    writer.print("public class " + actionName + " implements Action<" + responseName + ">");

    // Cache
    if (cacheable) {
      writer.print(", Cacheable ");
    }

    writer.println(" {");

    StringBuilder actionString = new StringBuilder();

    for (Entry<String, String> f : actionFields.entrySet()) {
      String type = f.getValue();
      String name = f.getKey();

      printGetterAndSetter(writer, type, name, actionName);

      if (actionString.length() > 0) {
        actionString.append(", ");
      }
      actionString.append(name + "=" + "\" + " + name + " + \"");
    }

    writer.println("  public " + actionName + "() {");
    writer.println("  }");

    writer.println("  public boolean equals(Object o) {");
    writer.println("    if (this == null) {");
    writer.println("        return false;");
    writer.println("    }");
    writer.println("    if (this == o) {");
    writer.println("        return true;");
    writer.println("    }");
    writer.println("    if (o instanceof " + actionName
        + ") { return o.hashCode() == hashCode(); } else { return super.equals(o);}");
    writer.println("  }");

    writer.println("  public int hashCode() {");
    writer.print("    return new HashCodeBuilder()");
    for (Entry<String, String> f : actionFields.entrySet()) {
      String name = f.getKey();
      writer.print(".append(" + name + ")");
    }
    writer.println(".toHashCode();");
    writer.println("  }");

    writer.println("  public String toString() {");
    writer.println("      if (!com.google.gwt.core.client.GWT.isScript()) {");
    writer.println("          return \"" + actionName + "[" + actionString.toString() + "]\";");
    writer.println("      }");
    writer.println("      return super.toString();");
    writer.println("  }");

    writer.println("}");
    writer.close();

    writer = getPrintWriter(packageName + "." + responseName);
    writer.println("package " + packageName + ";");

    writer.println("import com.guit.client.command.action.Response;");
    Generated.printGeneratedImport(writer);

    Generated.printGenerated(writer, simpleName);
    writer.println("public class " + responseName + " implements Response {");

    StringBuilder responseString = new StringBuilder();
    if (responseField != null) {
      printGetterAndSetter(writer, responseField, "result", responseName);

      if (responseString.length() > 0) {
        responseString.append(", ");
      }
      responseString.append("result = \" + result + \"");
    }

    writer.println("  public String toString() {");
    writer.println("      if (!com.google.gwt.core.client.GWT.isScript()) {");
    writer.println("          return \"" + responseName + "[" + responseString.toString() + "]\";");
    writer.println("      }");
    writer.println("      return super.toString();");
    writer.println("  }");

    writer.println("  public " + responseName + "() {");
    writer.println("  }");

    if (responseField != null) {
      writer.println("  public " + responseName + "(" + responseField + " result) {");
      writer.println("    this.result = result;");
      writer.println("  }");
    }

    writer.println("}");
    writer.close();
  }

  private static void printGetterAndSetter(PrintWriter writer, String type, String name,
      String className) {
    writer.println("  private " + type + " " + name + ";");

    writer.println("  public " + type + " get" + name.substring(0, 1).toUpperCase()
        + name.substring(1) + "() {");
    writer.println("    return " + name + ";");
    writer.println("  }");

    writer.println("  public void set" + name.substring(0, 1).toUpperCase() + name.substring(1)
        + "(" + type + " " + name + ") {");
    writer.println("    this." + name + " = " + name + ";");
    writer.println("  }");
  }

  public PrintWriter getPrintWriter(String qualifiedName) {
    try {
      return new PrintWriter(filer.createSourceFile(qualifiedName).openWriter());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
