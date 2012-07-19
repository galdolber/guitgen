package guitplugin;

import com.guit.client.apt.GwtController;
import com.guit.client.apt.GwtDisplay;

import org.xml.sax.SAXParseException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import guitplugin.guitview.GuitViewHelper;

@SupportedAnnotationTypes(value = {
    "com.guit.client.apt.GwtPresenter", "com.guit.client.apt.GwtController"})
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class GuitPresenterProcessor extends AbstractProcessor {

  String domPackage = "com.google.gwt.event.dom.client.";
  String sharedPackage = "com.google.gwt.event.logical.shared.";
  String aptPackage = "com.guit.client.apt.";

  private static final String containerError =
      "A gwt container method can only have 1 parameter of a type IsWidget";

  private Elements elementsUtil;

  private Filer filer;

  private Types typeUtils;
  private ProcessingEnvironment env;

  private void collectGetters(HashMap<String, ExecutableElement> validFields, TypeElement eventType) {
    if (eventType == null) {
      return;
    }

    Collection<ExecutableElement> classMethods =
        ElementFilter.methodsIn(elementsUtil.getAllMembers(eventType));
    for (ExecutableElement methodDeclaration : classMethods) {
      String name = methodDeclaration.getSimpleName().toString();
      TypeMirror returnType = methodDeclaration.getReturnType();
      if (!returnType.getKind().equals(TypeKind.VOID)) {
        if (name.startsWith("get")) {
          name = name.substring(3);
          name = name.substring(0, 1).toLowerCase() + name.substring(1);
          validFields.put(name, methodDeclaration);
        } else if (name.startsWith("is")
            && typeUtils.getPrimitiveType(TypeKind.BOOLEAN).equals(returnType)) {
          name = name.substring(2);
          name = name.substring(0, 1).toLowerCase() + name.substring(1);
          validFields.put(name, methodDeclaration);
        }
      }
    }

    Collection<? extends TypeMirror> superinterfaces = eventType.getInterfaces();
    for (TypeMirror interfaceType : superinterfaces) {
      collectGetters(validFields, (TypeElement) typeUtils.asElement(interfaceType));
    }

    if (eventType.getKind().equals(ElementKind.CLASS)) {
      TypeMirror superclass = eventType.getSuperclass();
      if (superclass != null) {
        collectGetters(validFields, (TypeElement) typeUtils.asElement(superclass));
      }
    }
  }

  protected String eventClassNameToEventName(String simpleName) {
    String eventName;
    eventName = simpleName.substring(0, simpleName.length() - 5);
    eventName = eventName.substring(0, 1).toLowerCase() + eventName.substring(1);
    return eventName;
  }

  protected String eventNameToEventClassName(String eventName) {
    eventName = eventName.substring(0, 1).toUpperCase() + eventName.substring(1) + "Event";
    return eventName;
  }

  private String fieldsToString(Set<String> names) {
    StringBuilder sb = new StringBuilder();
    for (String f : names) {
      if (sb.length() > 0) {
        sb.append(", ");
      }

      sb.append(f);
    }
    return sb.toString();
  }

  private HashMap<String, ExecutableElement> getValidFields(TypeElement eventType) {
    HashMap<String, ExecutableElement> validFields = new HashMap<String, ExecutableElement>();
    collectGetters(validFields, eventType);
    return validFields;
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    env = processingEnv;

    if (roundEnv.processingOver()) {
      return false;
    }

    this.elementsUtil = env.getElementUtils();
    this.filer = env.getFiler();
    this.typeUtils = env.getTypeUtils();

    for (TypeElement ann : annotations) {
      for (Element e : roundEnv.getElementsAnnotatedWith(ann)) {
        if (e.getKind().equals(ElementKind.CLASS)) {
          TypeElement d = (TypeElement) e;
          try {
            generateBinder(d);

            String annName = ann.getQualifiedName().toString();
            if (annName.equals(GwtController.class.getCanonicalName())) {
              generateControllerSuper(d);
              processController(d);
            } else if (annName.equals(aptPackage + "GwtPresenter")) {
              HashMap<String, HashMap<String, String>> fieldsMap = null;
              try {
                fieldsMap = GuitViewHelper.findUiFields(filer, d);
              } catch (SAXParseException ex) {
                printMessage(Kind.NOTE, String.format("Error parsing XML (line "
                    + ex.getLineNumber() + "): " + ex.getMessage()), d);
                printMessage(Kind.ERROR, String.format("Error parsing XML (line "
                    + ex.getLineNumber() + "): " + ex.getMessage()), d);
                continue;
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }

              generatePresenterSuper(d, fieldsMap);
              processController(d);
              for (Entry<String, HashMap<String, String>> entry : fieldsMap.entrySet()) {
                processPresenterWithOneXml(d, entry.getKey(), entry.getValue());
              }
            }

            // Containers
            for (ExecutableElement m : ElementFilter.methodsIn(elementsUtil.getAllMembers(d))) {
              if (m.getAnnotation(GwtDisplay.class) != null) {
                Collection<? extends VariableElement> parameters = m.getParameters();
                if (parameters.size() != 1) {
                  printMessage(Kind.ERROR, containerError, m);
                }
                VariableElement parameter = parameters.iterator().next();
                if (!parameter.asType().toString().equals("com.google.gwt.user.client.ui.IsWidget")) {
                  printMessage(Kind.ERROR, containerError, parameter);
                }
                processContainerMethod(d, m);
              }
            }
          } catch (Exception ex) {
            // printMessage(Kind.ERROR, d.getQualifiedName() + ": " +
            // ex.toString(), d);
            ex.printStackTrace();
          }
        }
      }
    }
    return false;
  }

  private void printMessage(Kind kind, String msg, Element e) {
    if (isEclipse()) {
      env.getMessager().printMessage(Kind.NOTE, msg, e);
    }
    env.getMessager().printMessage(kind, msg, e);
  }

  private boolean isEclipse() {
    try {
      filer.getResource(StandardLocation.SOURCE_PATH, "", "");
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  private void generateBinder(TypeElement classDeclaration) {
    String packageName = elementsUtil.getPackageOf(classDeclaration).getQualifiedName().toString();
    String simpleName = classDeclaration.getSimpleName().toString();
    String name = simpleName + "Binder";
    String qualifiedName = packageName + "." + name;
    PrintWriter writer = getPrintWriter(qualifiedName);
    writer.println("package " + packageName + ";");
    writer.println();
    Generated.printGeneratedImport(writer);
    writer.println("import com.guit.client.binder.GuitBinder;");
    writer.println();

    // Suppress warnings on generic types
    Collection<? extends TypeParameterElement> parameters = classDeclaration.getTypeParameters();
    if (parameters != null && parameters.size() > 0) {
      writer.println("@SuppressWarnings(\"rawtypes\")");
    }

    Generated.printGenerated(writer, simpleName);
    writer.println("public interface " + name + " extends GuitBinder<" + simpleName + "> {");
    writer.println("}");
    writer.close();
  }

  private void generatePresenterSuper(TypeElement classDeclaration,
      HashMap<String, HashMap<String, String>> fieldsMap) {

    Boolean elemental = false;

    // Validate autofocus and gwt editor
    Collection<? extends AnnotationMirror> annotations = classDeclaration.getAnnotationMirrors();
    for (AnnotationMirror a : annotations) {
      Element decl = a.getAnnotationType().asElement();
      if (decl == null) {
        continue;
      }

      String qualifiedName = ((TypeElement) decl).getQualifiedName().toString();
      if (qualifiedName.equals("com.guit.client.apt.GwtPresenter")) {
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> e : a.getElementValues()
            .entrySet()) {
          String name = e.getKey().getSimpleName().toString();
          if (name.equals("elemental")) {
            elemental = (Boolean) e.getValue().getValue();
          }
        }
      }
    }

    String packageName = elementsUtil.getPackageOf(classDeclaration).getQualifiedName().toString();
    String simpleName = classDeclaration.getSimpleName().toString();
    String name = simpleName + "Presenter";
    String qualifiedName = packageName + "." + name;

    PrintWriter writer = getPrintWriter(qualifiedName);
    writer.println("package " + packageName + ";");
    writer.println();

    Generated.printGenerated(writer, simpleName);
    String extendsPresenter = getExtendsPresenter(classDeclaration);
    TypeElement extendsPresenterElement = elementsUtil.getTypeElement(extendsPresenter);
    Set<String> allFields = getAllField(extendsPresenterElement);
    allFields.addAll(getAllField(classDeclaration));
    writer.println("public abstract class " + name + " extends " + extendsPresenter + "<"
        + simpleName + "Binder> {");

    printDriver(classDeclaration, writer);

    printDependencies(classDeclaration, writer);

    for (Entry<String, String> entry : fieldsMap.entrySet().iterator().next().getValue().entrySet()) {
      if (!allFields.contains(entry.getKey())) {
        if (entry.getValue().startsWith("com.guit.client.dom")) {
          writer.println();
          writer.println("  @com.guit.client.apt.Generated");
          writer.println("  @com.guit.client.binder.ViewField");
          writer.println("  "
              + (elemental ? getElementalElementFor(entry.getValue()) : entry.getValue()) + " "
              + entry.getKey() + ";");
        } else if (isCssResource(elementsUtil.getTypeElement(entry.getValue()))) {
          writer.println();
          writer.println("  @com.guit.client.apt.Generated");
          writer.println("  @com.guit.client.binder.ViewField");
          writer.println("  " + entry.getValue() + " " + entry.getKey() + ";");
        } else if (isPresenter(elementsUtil.getTypeElement(entry.getValue()))) {
          writer.println();
          writer.println("  @com.guit.client.apt.Generated");
          writer.println("  @com.google.inject.Inject");
          writer.println("  @com.guit.client.binder.ViewField(provided = true)");
          writer.println("  " + entry.getValue() + " " + entry.getKey() + ";");
        } else if (!entry.getValue().startsWith("com.google.gwt")) {
          writer.println();
          writer.println("  @com.guit.client.apt.Generated");
          writer.println("  @com.google.inject.Inject");
          writer.println("  @com.guit.client.binder.ViewField(provided = true)");
          writer.println("  " + entry.getValue() + " " + entry.getKey() + ";");
        } else {
          writer.println();
          writer.println("  @com.guit.client.apt.Generated");
          writer.println("  @com.guit.client.binder.ViewField");
          writer.println("  " + entry.getValue() + " " + entry.getKey() + ";");
        }
      }
    }

    writer.println("}");
    writer.close();
  }

  static HashMap<String, String> element2dom = new HashMap<String, String>();
  static {
    element2dom.put("frameset", "com.google.gwt.dom.client.FrameSetElement");
    element2dom.put("canvas", "com.google.gwt.dom.client.CanvasElement");
    element2dom.put("tr", "com.google.gwt.dom.client.TableRowElement");
    element2dom.put("textarea", "com.google.gwt.dom.client.TextAreaElement");
    element2dom.put("area", "com.google.gwt.dom.client.AreaElement");
    element2dom.put("select", "com.google.gwt.dom.client.SelectElement");
    element2dom.put("td", "com.google.gwt.dom.client.TableCellElement");
    element2dom.put("th", "com.google.gwt.dom.client.TableCellElement");
    element2dom.put("source", "com.google.gwt.dom.client.SourceElement");
    element2dom.put("label", "com.google.gwt.dom.client.LabelElement");
    element2dom.put("param", "com.google.gwt.dom.client.ParamElement");
    element2dom.put("ol", "com.google.gwt.dom.client.OListElement");
    element2dom.put("optgroup", "com.google.gwt.dom.client.OptGroupElement");
    element2dom.put("title", "com.google.gwt.dom.client.TitleElement");
    element2dom.put("span", "com.google.gwt.dom.client.SpanElement");
    element2dom.put("script", "com.google.gwt.dom.client.ScriptElement");
    element2dom.put("video", "com.google.gwt.dom.client.VideoElement");
    element2dom.put("audio", "com.google.gwt.dom.client.AudioElement");
    element2dom.put("form", "com.google.gwt.dom.client.FormElement");
    element2dom.put("ins", "com.google.gwt.dom.client.ModElement");
    element2dom.put("del", "com.google.gwt.dom.client.ModElement");
    element2dom.put("dl", "com.google.gwt.dom.client.DListElement");
    element2dom.put("map", "com.google.gwt.dom.client.MapElement");
    element2dom.put("base", "com.google.gwt.dom.client.BaseElement");
    element2dom.put("tbody", "com.google.gwt.dom.client.TableSectionElement");
    element2dom.put("tfoot", "com.google.gwt.dom.client.TableSectionElement");
    element2dom.put("thead", "com.google.gwt.dom.client.TableSectionElement");
    element2dom.put("pre", "com.google.gwt.dom.client.PreElement");
    element2dom.put("ul", "com.google.gwt.dom.client.UListElement");
    element2dom.put("img", "com.google.gwt.dom.client.ImageElement");
    element2dom.put("a", "com.google.gwt.dom.client.AnchorElement");
    element2dom.put("option", "com.google.gwt.dom.client.OptionElement");
    element2dom.put("button", "com.google.gwt.dom.client.ButtonElement");
    element2dom.put("object", "com.google.gwt.dom.client.ObjectElement");
    element2dom.put("legend", "com.google.gwt.dom.client.LegendElement");
    element2dom.put("body", "com.google.gwt.dom.client.BodyElement");
    element2dom.put("br", "com.google.gwt.dom.client.BRElement");
    element2dom.put("li", "com.google.gwt.dom.client.LIElement");
    element2dom.put("link", "com.google.gwt.dom.client.LinkElement");
    element2dom.put("iframe", "com.google.gwt.dom.client.IFrameElement");
    element2dom.put("table", "com.google.gwt.dom.client.TableElement");
    element2dom.put("frame", "com.google.gwt.dom.client.FrameElement");
    element2dom.put("p", "com.google.gwt.dom.client.ParagraphElement");
    element2dom.put("head", "com.google.gwt.dom.client.HeadElement");
    element2dom.put("col", "com.google.gwt.dom.client.TableColElement");
    element2dom.put("colgroup", "com.google.gwt.dom.client.TableColElement");
    element2dom.put("blockquote", "com.google.gwt.dom.client.QuoteElement");
    element2dom.put("q", "com.google.gwt.dom.client.QuoteElement");
    element2dom.put("caption", "com.google.gwt.dom.client.TableCaptionElement");
    element2dom.put("style", "com.google.gwt.dom.client.StyleElement");
    element2dom.put("input", "com.google.gwt.dom.client.InputElement");
    element2dom.put("h1", "com.google.gwt.dom.client.HeadingElement");
    element2dom.put("h2", "com.google.gwt.dom.client.HeadingElement");
    element2dom.put("h3", "com.google.gwt.dom.client.HeadingElement");
    element2dom.put("h4", "com.google.gwt.dom.client.HeadingElement");
    element2dom.put("h5", "com.google.gwt.dom.client.HeadingElement");
    element2dom.put("h6", "com.google.gwt.dom.client.HeadingElement");
    element2dom.put("div", "com.google.gwt.dom.client.DivElement");
    element2dom.put("meta", "com.google.gwt.dom.client.MetaElement");
    element2dom.put("fieldset", "com.google.gwt.dom.client.FieldSetElement");
    element2dom.put("hr", "com.google.gwt.dom.client.HRElement");
  }

  private String getElementalElementFor(String name) {
    try {
      String tagname = name.toLowerCase().substring(name.lastIndexOf(".") + 1);
      String gwtnode = element2dom.get(tagname);
      return "elemental.html." + gwtnode.substring(gwtnode.lastIndexOf(".") + 1);
    } catch (Exception e) {
      return "elemental.dom.Element";
    }
  }

  private boolean isPresenter(TypeElement type) {
    List<? extends AnnotationMirror> annotations = type.getAnnotationMirrors();
    for (AnnotationMirror ann : annotations) {
      Element asElement = ann.getAnnotationType().asElement();
      String qualifiedName = ((TypeElement) asElement).getQualifiedName().toString();
      if (qualifiedName.equals(aptPackage + "GwtPresenter")) {
        return true;
      }
    }

    for (TypeMirror i : type.getInterfaces()) {
      if (i.toString().equals("com.guit.client.Presenter")) {
        return true;
      }
    }
    Element superclass = typeUtils.asElement(type.getSuperclass());
    if (superclass != null) {
      return isPresenter((TypeElement) superclass);
    }
    return false;
  }

  private boolean isCssResource(TypeElement type) {
    for (TypeMirror i : type.getInterfaces()) {
      if (i.toString().equals("com.google.gwt.resources.client.CssResource")) {
        return true;
      }
    }
    Element superclass = typeUtils.asElement(type.getSuperclass());
    if (superclass != null) {
      return isCssResource((TypeElement) superclass);
    }
    return false;
  }

  private Set<String> getAllField(TypeElement clazz) {
    HashSet<String> allFields = new HashSet<String>();
    for (VariableElement f : ElementFilter.fieldsIn(elementsUtil.getAllMembers(clazz))) {
      allFields.add(f.getSimpleName().toString());
    }
    return allFields;
  }

  private void printDependencies(TypeElement classDeclaration, PrintWriter writer) {
    try {
      printDependenciesMaven(classDeclaration, writer, filer.getResource(
          StandardLocation.SOURCE_PATH,
          elementsUtil.getPackageOf(classDeclaration).getQualifiedName().toString(),
          classDeclaration.getSimpleName() + ".java").getCharContent(true).toString());
    } catch (Exception e) {
      printDependenciesEclipse(classDeclaration, writer);
    }
  }

  private void printDependenciesMaven(TypeElement classDeclaration, PrintWriter writer,
      String source) {
    HashMap<String, String> imports = new HashMap<String, String>();

    String[] lines = source.split("\\r?\\n");
    String injections = null;
    for (String l : lines) {
      l = l.trim();
      if (l.isEmpty()) {
        continue;
      }
      if (l.startsWith("import ")) {
        l = l.substring(7, l.length() - 1);
        imports.put(l.substring(l.lastIndexOf(".") + 1), l);
      } else if (l.startsWith("@Injections")) {
        injections = l;
        String substring = injections.substring(13, injections.length() - 2);
        for (String clazz : substring.split(",")) {
          clazz = clazz.trim();
          clazz = clazz.substring(0, clazz.length() - 6);

          writer.println();
          if (imports.containsKey(clazz)) {
            clazz = imports.get(clazz);
          }
          String name = clazz.substring(clazz.lastIndexOf(".") + 1);
          writer.println("  @com.google.inject.Inject");
          writer.println("  " + clazz + " " + name.substring(0, 1).toLowerCase()
              + name.substring(1) + ";");
        }
        return;
      }
    }
  }

  private void printDependenciesEclipse(TypeElement classDeclaration, PrintWriter writer) {
    List<? extends AnnotationMirror> annotations = classDeclaration.getAnnotationMirrors();
    for (AnnotationMirror a : annotations) {
      String annName =
          ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
      if (annName.equals("com.guit.client.apt.Injections")) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values = a.getElementValues();
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> v : values.entrySet()) {
          if (v.getKey().getSimpleName().toString().equals("value")) {
            List<?> classes = (List<?>) v.getValue().getValue();
            for (Object inject : classes) {
              String clazz = inject.toString();
              if (clazz.equals("<error>")) {
                return;
              }
              writer.println();
              if (clazz.endsWith(".class")) {
                clazz = clazz.substring(0, clazz.length() - 6);
              }
              String name = clazz.substring(clazz.lastIndexOf(".") + 1);
              writer.println("  @com.google.inject.Inject");
              writer.println("  " + clazz + " " + name.substring(0, 1).toLowerCase()
                  + name.substring(1) + ";");
            }
            return;
          }
        }
      }
    }
  }

  private void printDriver(TypeElement classDeclaration, PrintWriter writer) {
    Collection<? extends AnnotationMirror> annotations = classDeclaration.getAnnotationMirrors();
    for (AnnotationMirror a : annotations) {
      Element decl = a.getAnnotationType().asElement();
      if (decl == null) {
        continue;
      }

      String qualifiedName = ((TypeElement) decl).getQualifiedName().toString();
      if (qualifiedName.equals("com.guit.client.binder.GwtEditor")) {
        String pojo = null;
        String base = "com.google.gwt.editor.client.SimpleBeanEditorDriver";
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : a
            .getElementValues().entrySet()) {
          String name = entry.getKey().getSimpleName().toString();
          if (name.equals("pojo")) {
            pojo = entry.getValue().getValue().toString();
          } else if (name.equals("base")) {
            base = entry.getValue().getValue().toString();
          }
        }
        if (pojo != null) {
          writer.println();
          writer.println("  " + base + "<" + pojo + ", ?> driver;");
        }
        return;
      }
    }
  }

  private void generateControllerSuper(TypeElement classDeclaration) {
    String packageName = elementsUtil.getPackageOf(classDeclaration).getQualifiedName().toString();
    String simpleName = classDeclaration.getSimpleName().toString();
    String name = simpleName + "Controller";
    String qualifiedName = packageName + "." + name;
    PrintWriter writer = getPrintWriter(qualifiedName);
    writer.println("package " + packageName + ";");
    writer.println();
    writer.println("import com.guit.client.GuitController;");
    Generated.printGeneratedImport(writer);
    writer.println();

    String binderName = simpleName + "Binder";
    Generated.printGenerated(writer, simpleName);
    writer.println("public abstract class " + name + " extends GuitController<" + binderName
        + "> {");
    writer.println("}");
    writer.close();
  }

  private String getExtendsPresenter(TypeElement classDeclaration) {
    Collection<? extends AnnotationMirror> annotations = classDeclaration.getAnnotationMirrors();
    for (AnnotationMirror a : annotations) {
      Element decl = a.getAnnotationType().asElement();
      if (decl == null) {
        continue;
      }

      String qualifiedName = ((TypeElement) decl).getQualifiedName().toString();
      if (qualifiedName.equals("com.guit.client.apt.GwtPresenter")) {
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : a
            .getElementValues().entrySet()) {
          if (entry.getKey().getSimpleName().toString().equals("extendsPresenter")) {
            return entry.getValue().getValue().toString();
          }
        }
      }
    }
    return "com.guit.client.GuitPresenter";
  }

  @SuppressWarnings("unchecked")
  private void processPresenterWithOneXml(TypeElement classDeclaration, String viewName,
      HashMap<String, String> hashMap) {

    // Validate autofocus and gwt editor
    Collection<? extends AnnotationMirror> annotations = classDeclaration.getAnnotationMirrors();
    for (AnnotationMirror a : annotations) {
      Element decl = a.getAnnotationType().asElement();
      if (decl == null) {
        continue;
      }

      String qualifiedName = ((TypeElement) decl).getQualifiedName().toString();
      if (qualifiedName.equals("com.guit.client.apt.GwtPresenter")) {
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> e : a.getElementValues()
            .entrySet()) {
          String name = e.getKey().getSimpleName().toString();
          if (name.equals("autofocus")) {
            String field = (String) e.getValue().getValue();
            if (!hashMap.containsKey(field)) {
              printMessage(Kind.ERROR, viewName + ". " + "The field '" + field
                  + "' is not declared in the view. Valid fields: "
                  + fieldsToString(hashMap.keySet()), e.getKey());
            }
          }
        }
      }
    }

    {
      for (VariableElement f : ElementFilter.fieldsIn(elementsUtil.getAllMembers(classDeclaration))) {
        Collection<? extends AnnotationMirror> annotationsMirror = f.getAnnotationMirrors();

        AnnotationMirror viewField = null;
        boolean isGenerated = false;
        for (AnnotationMirror mirror : annotationsMirror) {
          Element declaration = mirror.getAnnotationType().asElement();

          // ViewField
          if (declaration.toString().equals("com.guit.client.binder.ViewField")) {
            viewField = mirror;
          } else if (declaration.toString().equals("com.guit.client.apt.Generated")) {
            isGenerated = true;
          }
        }

        if (viewField != null && !isGenerated) {
          String fieldName = null;
          Boolean provided = false;
          Map<? extends ExecutableElement, ? extends AnnotationValue> map =
              viewField.getElementValues();
          for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : map.entrySet()) {
            String simpleName = entry.getKey().getSimpleName().toString();
            if (simpleName.equals("name")) {
              fieldName = (String) entry.getValue().getValue();
            }

            if (entry.getKey().getSimpleName().equals("provided")) {
              provided = (Boolean) entry.getValue().getValue();
            }
          }

          ElementKind kind = f.getKind();
          if (!provided && kind.isClass()) {
            printMessage(Kind.ERROR, viewName + ". "
                + "The type of a @ViewField must be an interface", f);
          }

          if (fieldName == null) {
            fieldName = f.getSimpleName().toString();
          }

          if (!hashMap.containsKey(fieldName)) {
            printMessage(Kind.ERROR, viewName + ". " + "The field '" + fieldName
                + "' is not declared in the view. Valid fields: "
                + fieldsToString(hashMap.keySet()), f);
          }
          // else {
          // if (hashMap.get(fieldName).startsWith("com.guit.client.dom")
          // && f.getEnclosingElement().toString().equals(
          // classDeclaration.getQualifiedName().toString())) {
          // printMessage(Kind.ERROR, viewName + ". " + "The field '" + fieldName
          // + "' is already declared on the super class", f);
          // }
          // }
        }
      }
    }

    Collection<ExecutableElement> methods =
        ElementFilter.methodsIn(elementsUtil.getAllMembers(classDeclaration));
    for (ExecutableElement m : methods) {

      Collection<? extends AnnotationMirror> annotationsMirror = m.getAnnotationMirrors();

      boolean isViewHandler = false;
      boolean hasViewFields = false;
      TypeElement eventType = null;
      ExecutableElement eventTypeDeclaration = null;
      for (AnnotationMirror mirror : annotationsMirror) {
        Element declaration = mirror.getAnnotationType().asElement();

        // ViewHandler
        if (((TypeElement) declaration).getQualifiedName().toString().equals(
            "com.guit.client.binder.ViewHandler")) {
          isViewHandler = true;
          Map<? extends ExecutableElement, ? extends AnnotationValue> map =
              mirror.getElementValues();
          for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : map.entrySet()) {
            String simpleName = entry.getKey().getSimpleName().toString();
            if (simpleName.equals("event")) {
              eventTypeDeclaration = entry.getKey();
              Object value = entry.getValue().getValue();
              eventType = (TypeElement) typeUtils.asElement(((TypeMirror) value));
            } else if (simpleName.equals("fields")) {
              hasViewFields = true;
              List<AnnotationValue> viewFields =
                  (List<AnnotationValue>) entry.getValue().getValue();

              for (AnnotationValue value : viewFields) {
                String field = (String) value.getValue();
                if (!hashMap.containsKey(field)) {
                  printMessage(Kind.ERROR, viewName + ". " + "The field '" + field
                      + "' is not declared in the view. Valid fields: "
                      + fieldsToString(hashMap.keySet()), m);
                }
              }
            }
          }
        }
      }

      if (isViewHandler) {
        if (!hasViewFields) {
          List<String> fields =
              new ArrayList<String>(Arrays.asList(m.getSimpleName().toString().split("[$]")));
          int lastIndex = fields.size() - 1;
          String eventName = fields.get(lastIndex);

          // Validate event name
          if (eventType != null
              && !"com.google.gwt.event.shared.GwtEvent".equals(eventType.getQualifiedName())) {
            String simpleName = eventType.getSimpleName().toString();
            if (!simpleName.endsWith("Event")) {
              printMessage(Kind.ERROR, viewName + ". "
                  + "The event type does not match with the convention, it must end with 'Event'",
                  eventTypeDeclaration);
            }

            String currentEventName = eventClassNameToEventName(simpleName);
            if (!eventName.equals(currentEventName)) {
              printMessage(Kind.ERROR, viewName + ". " + "The method name must end with '"
                  + currentEventName + "'", m);
            }
          } else {
            String currentPackage =
                elementsUtil.getPackageOf(classDeclaration).getQualifiedName().toString()
                    + ".event.";
            String eventClassName = eventNameToEventClassName(eventName);
            eventType = elementsUtil.getTypeElement(domPackage + eventClassName);
            if (eventType == null) {
              eventType = elementsUtil.getTypeElement(sharedPackage + eventClassName);
            }
            if (eventType == null) {
              eventType = elementsUtil.getTypeElement(currentPackage + eventClassName);
            }

            if (eventType == null) {
              printMessage(Kind.WARNING,
                  "The event cannot be found. Do you see any typo in the name? '" + eventName
                      + "'. i.e: ClickEvent -> click", m);
            }
          }

          fields.remove(lastIndex);
          for (String part : fields) {
            if (!hashMap.containsKey(part)) {
              printMessage(Kind.ERROR, viewName + ". " + "The field '" + part
                  + "' is not declared in the view. Valid fields: "
                  + fieldsToString(hashMap.keySet()), m);
              break;
            }
          }
        } else {
          if (eventType == null) {
            printMessage(
                Kind.ERROR,
                viewName
                    + ". "
                    + "When using the @ViewFields annotation you need to specify the event type on the @ViewHandler annotation",
                m);
          }
        }

        // Parameters
        Collection<? extends VariableElement> parameters = m.getParameters();
        for (VariableElement p : parameters) {

          boolean isAttribute = false;
          Collection<? extends AnnotationMirror> parameterAnnotations = p.getAnnotationMirrors();
          for (AnnotationMirror annotationMirror : parameterAnnotations) {
            if (((TypeElement) annotationMirror.getAnnotationType().asElement()).getQualifiedName()
                .toString().equals("com.guit.client.binder.Attribute")) {
              isAttribute = true;
              break;
            }
          }
          if (isAttribute) {
            if (p.asType().getKind().isPrimitive()) {
              printMessage(
                  Kind.ERROR,
                  viewName
                      + ". "
                      + "@Attribute parameters cannot be of a primitive type. The type must implement valueOf(String value)",
                  p);
            }
            continue;
          }

          // String name = p.getSimpleName().toString();
          // String[] parts = name.split("[$]");
          //
          // HashMap<String, ExecutableElement> validFields = getValidFields(eventType);
          // StringBuilder sb = new StringBuilder();
          // for (String part : parts) {
          //
          // if (sb.length() > 0) {
          // sb.append(".");
          // }
          //
          // if (!validFields.keySet().contains(part)) {
          // printMessage(Kind.ERROR, viewName + ". " + "The event '"
          // + eventType.getQualifiedName() + "' does not have a getter method for '"
          // + sb.toString() + part + "'", p);
          // break;
          // }
          //
          // TypeMirror returnClassType = validFields.get(part).getReturnType();
          // if (typeUtils.asElement(returnClassType) instanceof TypeElement) {
          // validFields = getValidFields((TypeElement) typeUtils.asElement(returnClassType));
          // } else {
          // // TODO Support for generics
          // }
          // sb.append(part);
          // }
        }

        // TODO Check types
      }
    }
  }

  private void processController(TypeElement classDeclaration) {
    Collection<ExecutableElement> methods =
        ElementFilter.methodsIn(elementsUtil.getAllMembers(classDeclaration));
    for (ExecutableElement m : methods) {

      Collection<? extends AnnotationMirror> annotationsMirror = m.getAnnotationMirrors();

      TypeElement eventType = null;
      ExecutableElement eventTypeDeclaration = null;
      boolean isEventBusHandler = false;
      for (AnnotationMirror mirror : annotationsMirror) {
        Element declaration = mirror.getAnnotationType().asElement();

        if (declaration == null) {
          continue;
        }

        // EventBusHandler
        if (((TypeElement) declaration).getQualifiedName().toString().equals(
            "com.guit.client.binder.EventBusHandler")) {
          isEventBusHandler = true;
          Map<? extends ExecutableElement, ? extends AnnotationValue> map =
              mirror.getElementValues();
          for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : map.entrySet()) {
            eventTypeDeclaration = entry.getKey();
            try {
              Object value = entry.getValue().getValue();
              eventType = (TypeElement) typeUtils.asElement(((TypeMirror) value));;
            } catch (ClassCastException e) {
              continue;
            }
          }
          break;
        }
      }

      if (isEventBusHandler) {
        List<String> fields =
            new ArrayList<String>(Arrays.asList(m.getSimpleName().toString().split("[$]")));
        if (fields.size() != 2 || (!fields.get(0).equals("eventBus") && !fields.get(0).isEmpty())) {
          printMessage(Kind.ERROR,
              "The method name must be 'eventBus${eventname}' or '${eventname}'", m);
          continue;
        }

        String eventName = fields.get(1);

        // Validate event name
        if (eventType != null
            && !"com.google.gwt.event.shared.GwtEvent".equals(eventType.getQualifiedName())) {
          String simpleName = eventType.getSimpleName().toString();
          if (!simpleName.endsWith("Event")) {
            printMessage(Kind.ERROR,
                "The event type does not match with the convention, it must end with 'Event'",
                eventTypeDeclaration);
          }

          String currentEventName = eventClassNameToEventName(simpleName);
          if (!eventName.equals(currentEventName)) {
            printMessage(Kind.ERROR, "The method name must end with '" + currentEventName + "'", m);
          }
        } else {
          // String currentPackage =
          // classDeclaration.getPackage().getQualifiedName() + ".event.";
          // String eventClassName = eventNameToEventClassName(eventName);
          // eventType = env.getTypeDeclaration(domPackage + eventClassName);
          // if (eventType == null) {
          // eventType = env.getTypeDeclaration(sharedPackage + eventClassName);
          // }
          // if (eventType == null) {
          // eventType = env.getTypeDeclaration(currentPackage +
          // eventClassName);
          // }
          //
          // if (eventType == null) {
          // messager.printWarning(m.getPosition(),
          // "The event cannot be found. Do you see any typo in the name? '" +
          // eventName
          // + "'. i.e: ClickEvent -> click");
          // }
          continue;
        }

        // Parameters
        Collection<? extends VariableElement> parameters = m.getParameters();
        for (VariableElement p : parameters) {
          Collection<? extends AnnotationMirror> parameterAnnotations = p.getAnnotationMirrors();
          boolean hasAttribute = false;
          for (AnnotationMirror annotationMirror : parameterAnnotations) {
            if (((TypeElement) annotationMirror.getAnnotationType().asElement()).getQualifiedName()
                .toString().equals("com.guit.client.binder.Attribute")) {
              hasAttribute = true;
              break;
            }
          }
          if (hasAttribute) {
            printMessage(Kind.ERROR,
                "@Attribute parameters are not allowed in EventBusHandler methods", p);
            continue;
          }

          String name = p.getSimpleName().toString();
          String[] parts = name.split("[$]");

          HashMap<String, ExecutableElement> validFields = getValidFields(eventType);
          StringBuilder sb = new StringBuilder();
          for (String part : parts) {
            if (sb.length() > 0) {
              sb.append(".");
            }

            if (!validFields.keySet().contains(part)) {
              printMessage(Kind.ERROR, "The event '" + eventType.getQualifiedName()
                  + "' does not have a getter method for '" + sb.toString() + part
                  + "'. Valid fields: " + validFields.keySet().toString(), p);
              continue;
            }

            TypeMirror retType = validFields.get(part).getReturnType();
            validFields = getValidFields((TypeElement) typeUtils.asElement(retType));
            sb.append(part);
          }
        }

        // TODO Check types
      }
    }
  }

  private void processContainerMethod(TypeElement d, ExecutableElement m) {
    // Get name
    String containerName = null;
    Collection<? extends AnnotationMirror> mirrors = m.getAnnotationMirrors();
    for (AnnotationMirror am : mirrors) {
      if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().toString().equals(
          GwtDisplay.class.getCanonicalName())) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values = am.getElementValues();
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values
            .entrySet()) {
          if (entry.getKey().getSimpleName().toString().equals("value")) {
            containerName = (String) entry.getValue().getValue();
          }
        }
      }
    }

    if (containerName == null) {
      return;
    }

    // Generate annotation
    String name = containerName;
    String packageName = elementsUtil.getPackageOf(d).getQualifiedName().toString();
    String qualifiedName = packageName + "." + name;
    PrintWriter writer = getPrintWriter(qualifiedName);
    writer.println("package " + packageName + ";");
    writer.println();
    writer.println("import java.lang.annotation.Documented;");
    writer.println("import java.lang.annotation.Retention;");
    writer.println("import java.lang.annotation.RetentionPolicy;");
    writer.println();
    writer.println("import com.google.inject.BindingAnnotation;");
    Generated.printGeneratedImport(writer);
    writer.println();
    writer.println("@BindingAnnotation");
    writer.println("@Retention(RetentionPolicy.RUNTIME)");
    writer.println("@Documented");
    writer.println();
    Generated.printGenerated(writer, d.getSimpleName().toString());
    writer.println("public @interface " + name + "{");
    writer.println("}");
    writer.close();

    // Generate provider
    name = containerName + "Provider";
    qualifiedName = packageName + "." + name;
    writer = getPrintWriter(qualifiedName);
    writer.println("package " + packageName + ";");
    writer.println();
    writer.println("import com.google.gwt.user.client.ui.AcceptsOneWidget;");
    writer.println("import com.google.gwt.user.client.ui.IsWidget;");
    writer.println("import com.google.inject.Inject;");
    writer.println("import com.google.inject.Provider;");
    writer.println("import com.google.inject.Singleton;");
    Generated.printGeneratedImport(writer);
    writer.println();
    writer.println("@Singleton");
    Generated.printGenerated(writer, d.getSimpleName().toString());
    writer.println("public class " + name
        + " implements Provider<AcceptsOneWidget>, AcceptsOneWidget {");
    writer.println();
    writer.println("  @Inject");
    writer.println("  " + d.getQualifiedName() + " owner;");
    writer.println();
    writer.println("  @Override");
    writer.println("  public void setWidget(IsWidget w) {");
    writer.println("    owner." + m.getSimpleName() + "(w);");
    writer.println("  }");
    writer.println();
    writer.println("  @Override");
    writer.println("  public AcceptsOneWidget get() {");
    writer.println("    return this;");
    writer.println("  }");
    writer.println("}");
    writer.close();

    // Generate module
    name = containerName + "Module";
    qualifiedName = packageName + "." + name;
    writer = getPrintWriter(qualifiedName);
    writer.println("package " + packageName + ";");
    writer.println();
    writer.println("import com.google.gwt.inject.client.AbstractGinModule;");
    writer.println("import com.google.gwt.user.client.ui.AcceptsOneWidget;");
    Generated.printGeneratedImport(writer);
    writer.println();
    Generated.printGenerated(writer, d.getSimpleName().toString());
    writer.println("public class " + name + " extends AbstractGinModule {");
    writer.println();
    writer.println("  @Override");
    writer.println("  protected void configure() {");
    writer.println("    bind(AcceptsOneWidget.class).annotatedWith(" + containerName
        + ".class).toProvider(" + containerName + "Provider.class);");
    writer.println("  }");
    writer.println("}");
    writer.close();
  }

  public PrintWriter getPrintWriter(String qualifiedName) {
    try {
      JavaFileObject createSourceFile = filer.createSourceFile(qualifiedName);
      return new PrintWriter(createSourceFile.openWriter());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
