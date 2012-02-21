package guitplugin.guitview;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;

import java.util.ArrayList;
import java.util.HashMap;

import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;

public class GuitViewHelper {
  static final String BINDER_URI = "urn:ui:com.google.gwt.uibinder";

  private static void findUiBundleFields(HashMap<String, String> uiFields, NodeList childNodes,
      String binderPrefix) {
    for (int n = 0; n < childNodes.getLength(); n++) {
      Node item = childNodes.item(n);
      if (item.getNodeType() == Node.ELEMENT_NODE) {
        Element element = (Element) item;

        String field = element.getAttribute("field");
        String namespace = element.getAttribute("type");

        String name = item.getNodeName();
        if (name.startsWith(binderPrefix)) {
          name = name.substring(binderPrefix.length() + 1);
          if (name.equals("style")) {
            if (field.isEmpty()) {
              field = "style";
            }

            if (namespace.isEmpty()) {
              namespace = "com.google.gwt.resources.client.CssResource";
            }
          } else if (name.equals("image")) {
            namespace = "com.google.gwt.resources.client.ImageResource";
          } else if (name.equals("data")) {
            namespace = "com.google.gwt.resources.client.DataResource";
          }
          uiFields.put(field, namespace);
        }
      }
    }
  }

  public static HashMap<String, HashMap<String, String>> findUiFields(Filer filer,
      TypeElement classDeclaration) throws SAXParseException {
    HashMap<String, HashMap<String, String>> all = new HashMap<String, HashMap<String, String>>();

    ArrayList<LazyDocument> documents = getW3cDoc(filer, classDeclaration);

    for (LazyDocument document : documents) {
      Element documentElement = document.get().getDocumentElement();
      HashMap<String, String> list = new HashMap<String, String>();
      String binderPrefix = documentElement.lookupPrefix(BINDER_URI);
      String uiFieldAttribute = binderPrefix + ":field";
      findUiFields(list, documentElement, uiFieldAttribute);
      findUiBundleFields(list, documentElement.getChildNodes(), binderPrefix);
      all.put(document.getFileName(), list);
    }

    return all;
  }

  private static void findUiFields(HashMap<String, String> uiFields, Element node,
      String uiFieldAttribute) {
    if (node.hasAttribute(uiFieldAttribute)) {
      String prefix = node.getPrefix();
      String namespace = node.lookupNamespaceURI(prefix);
      String name;
      if (namespace != null) {
        // Widgets
        if (namespace.startsWith("urn:import:")) {
          namespace = namespace.substring(11);
        } else {
          throw new IllegalStateException(String
              .format("Bad namespace. Found: %s", node.toString()));
        }
        name = node.getNodeName().substring(prefix.length() + 1);
      } else {
        // Html elements
        name = node.getNodeName();
        name = name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
        namespace = "com.guit.client.dom";
      }
      uiFields.put(node.getAttribute(uiFieldAttribute), namespace + "." + name);
    }

    NodeList children = node.getChildNodes();
    for (int n = 0; n < children.getLength(); n++) {
      Node item = children.item(n);
      if (item.getNodeType() == Node.ELEMENT_NODE) {
        findUiFields(uiFields, (Element) item, uiFieldAttribute);
      }
    }
  }

  private static ArrayList<LazyDocument> getW3cDoc(Filer filer, TypeElement classDeclaration)
      throws SAXParseException {
    return new W3cDomHelper().documentFor(filer, classDeclaration);
  }
}
