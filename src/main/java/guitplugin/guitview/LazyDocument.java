package guitplugin.guitview;

import org.w3c.dom.Document;

public interface LazyDocument {

  Document get();

  String getFileName();
}
