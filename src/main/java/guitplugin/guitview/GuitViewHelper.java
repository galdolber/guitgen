package guitplugin.guitview;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;

public class GuitViewHelper {
  static final String BINDER_URI = "urn:ui:com.google.gwt.uibinder";

  private static void findUiBundleFields(Set<String> uiFields, NodeList childNodes,
      String binderPrefix, boolean onlyNames) {
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
          if (!onlyNames) {
            uiFields.add("@UiField " + namespace + " " + field + ";");
          } else {
            uiFields.add(field);
          }
        }
      }
    }
  }

  public static HashMap<String, Set<String>> findUiFields(Filer filer,
      TypeElement classDeclaration, boolean onlyNames) throws SAXParseException {
    HashMap<String, Set<String>> all = new HashMap<String, Set<String>>();

    ArrayList<LazyDocument> documents = getW3cDoc(filer, classDeclaration);

    for (LazyDocument document : documents) {
      Element documentElement = document.get().getDocumentElement();
      Set<String> list = new HashSet<String>();
      String binderPrefix = documentElement.lookupPrefix(BINDER_URI);
      String uiFieldAttribute = binderPrefix + ":field";
      findUiFields(list, documentElement, uiFieldAttribute, onlyNames);
      findUiBundleFields(list, documentElement.getChildNodes(), binderPrefix, onlyNames);
      all.put(document.getFileName(), list);
    }

    return all;
  }

  private static void findUiFields(Set<String> uiFields, Element node, String uiFieldAttribute,
      boolean onlyNames) {
    if (!onlyNames) {
      if (node.hasAttribute(uiFieldAttribute)) {
        String prefix = node.getPrefix();
        String namespace = node.lookupNamespaceURI(prefix);
        String name;
        if (namespace != null) {
          // Widgets
          if (namespace.startsWith("urn:import:")) {
            namespace = namespace.substring(11);
          } else {
            throw new IllegalStateException(String.format("Bad namespace. Found: %s", node
                .toString()));
          }
          name = node.getNodeName().substring(prefix.length() + 1);
        } else {
          // Html elements
          name = "Element";
          namespace = "com.google.gwt.dom.client";
        }
        uiFields.add("@UiField " + namespace + "." + name + " "
            + node.getAttribute(uiFieldAttribute) + ";");
      }
    } else {
      if (node.hasAttribute(uiFieldAttribute)) {
        uiFields.add(node.getAttribute(uiFieldAttribute));
      }
    }

    NodeList children = node.getChildNodes();
    for (int n = 0; n < children.getLength(); n++) {
      Node item = children.item(n);
      if (item.getNodeType() == Node.ELEMENT_NODE) {
        findUiFields(uiFields, (Element) item, uiFieldAttribute, onlyNames);
      }
    }
  }

  private static ArrayList<LazyDocument> getW3cDoc(Filer filer, TypeElement classDeclaration)
      throws SAXParseException {
    return new W3cDomHelper().documentFor(filer, classDeclaration);
  }
}
