/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package guitplugin.guitview;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes the sax xml parser use the {@link ResourceOracle}.
 * <p>
 * Does special case handling of GWT specific DTDs to be fetched from our download site. If the requested uri starts
 * with <code>http://dl.google.com/gwt/DTD/</code> (or one or two others), provides the contents from a built in
 * resource rather than allowing sax to make a network request.
 */
class GwtResourceEntityResolver implements EntityResolver {
    private static final Set<String> EXTERNAL_PREFIXES = new HashSet<String>();
    static {
        EXTERNAL_PREFIXES.add("http://google-web-toolkit.googlecode.com/files/");
        EXTERNAL_PREFIXES.add("http://dl.google.com/gwt/DTD/");
        EXTERNAL_PREFIXES.add("https://dl-ssl.google.com/gwt/DTD/");
    }

    private static final String RESOURCES = "guitplugin/guitview/resources/";

    public GwtResourceEntityResolver() {
    }

    public InputSource resolveEntity(String publicId, String systemId) {
        String matchingPrefix = findMatchingPrefix(systemId);
        if (matchingPrefix == null) {
            return null;
        }
        systemId = systemId.substring(matchingPrefix.length());

        String path = RESOURCES + systemId;
        return find(publicId, path);
    }

    HashMap<String, InputSource> cache = new HashMap<String, InputSource>();

    private InputSource find(String publicId, String path) {
        String key = publicId + "_" + path;
        if (cache.containsKey(key)) {
            return cache.get(key);
        } else {
            InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
            if (stream != null) {
                InputSource inputSource = new InputSource(new InputStreamReader(stream));
                inputSource.setPublicId(publicId);
                inputSource.setSystemId(path);
                cache.put(key, inputSource);
                return inputSource;
            } else {
                cache.put(key, null);
                return null;
            }
        }
    }

    private String findMatchingPrefix(String systemId) {
        for (String prefix : EXTERNAL_PREFIXES) {
            if (systemId.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }
}
