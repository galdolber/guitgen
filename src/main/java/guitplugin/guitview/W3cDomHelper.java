package guitplugin.guitview;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.processing.Filer;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Simplifies instantiation of the w3c XML parser, in just the style that
 * UiBinder likes it. Used by both prod and test.
 */
public class W3cDomHelper {
  private final DocumentBuilderFactory factory;

  public W3cDomHelper() {
    factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setExpandEntityReferences(true);
  }

  DocumentBuilder getBuilder() {
    DocumentBuilder builder;
    try {
      builder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    }
    builder.setEntityResolver(new GwtResourceEntityResolver());
    builder.setErrorHandler(new ErrorHandler() {

      @Override
      public void error(SAXParseException exception) throws SAXException {
        exception.printStackTrace();
      }

      @Override
      public void fatalError(SAXParseException exception) throws SAXException {
        exception.printStackTrace();
      }

      @Override
      public void warning(SAXParseException exception) throws SAXException {
        exception.printStackTrace();
      }
    });
    return builder;
  }

  public ArrayList<LazyDocument> documentFor(Filer filer, TypeElement classDeclaration)
      throws SAXParseException {
    String viewName = classDeclaration.getSimpleName() + ".ui.xml";
    Collection<? extends AnnotationMirror> mirrors = classDeclaration.getAnnotationMirrors();
    for (AnnotationMirror mirror : mirrors) {
      if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString()
          .equals("com.guit.client.ViewTemplate")) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
            mirror.getElementValues();
        for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values
            .entrySet()) {
          viewName = (String) entry.getValue().getValue();
          break;
        }
        break;
      }
    }

    String basePackage = classDeclaration.getQualifiedName().toString();
    basePackage = basePackage.substring(0, basePackage.lastIndexOf("."));

    ArrayList<LazyDocument> documents = new ArrayList<LazyDocument>();

    // Try to locate in the same package
    locateFile(filer, basePackage, viewName, documents);

    if (documents.size() == 0) {
      throw new RuntimeException("The view '" + classDeclaration.getQualifiedName()
          + ".ui.xml' was not found");
    }

    return documents;
  }

  public void locateFile(Filer filer, final String pkg, final String file,
      ArrayList<LazyDocument> documents) {

    try {
      InputStream openInputStream = null;
      try {
        openInputStream = filer.getResource(StandardLocation.SOURCE_PATH, pkg + ".view", file).openInputStream();
      } catch (Exception e) {
        openInputStream = filer.getResource(StandardLocation.CLASS_OUTPUT, pkg + ".view", file).openInputStream();        
      }
      final Document document = getBuilder().parse(openInputStream);
      documents.add(new LazyDocument() {
        @Override
        public Document get() {
          return document;
        }

        @Override
        public String getFileName() {
          return file;
        }
      });
      return;
    } catch (FileNotFoundException e) {
      throw new RuntimeException("View file not found for " + pkg + ".view." + file, e);
    } catch (SAXException e) {
      throw new RuntimeException("XML error found in " + pkg + ".view." + file, e);
    } catch (Exception e) {
      throw new RuntimeException(pkg + ".view." + file, e);
    }
  }

  public static boolean isWindows() {
    String os = System.getProperty("os.name").toLowerCase();
    return (os.indexOf("win") >= 0);
  }
}
