package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.OutputKeys;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.File;
import java.io.IOException;
import org.xml.sax.SAXException;

/**
 * Manages the first-run check using an XML configuration file to enable back-filling on initial launch.
 */
public class FirstRunCheck {
    private static final String CONFIG_FILE = "config.xml";

    public static boolean isFirstRun() {
        File configFile = new File(CONFIG_FILE);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder;

        try {
            dBuilder = dbFactory.newDocumentBuilder();
            if (configFile.exists()) {
                // Parse the existing XML file
                Document doc = dBuilder.parse(configFile);
                doc.getDocumentElement().normalize();
                String hasRun = doc.getDocumentElement().getAttribute("hasRun");
                return !"true".equals(hasRun);
            } else {
                // Create new XML file with hasRun set to true
                Document doc = dBuilder.newDocument();
                Element rootElement = doc.createElement("config");
                rootElement.setAttribute("hasRun", "true");
                doc.appendChild(rootElement);

                // Write the content into XML file
                TransformerFactory transformerFactory = TransformerFactory.newInstance();
                Transformer transformer = transformerFactory.newTransformer();
                DOMSource source = new DOMSource(doc);
                StreamResult result = new StreamResult(configFile);
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.transform(source, result);

                return true; // It's the first run
            }
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
            e.printStackTrace();
        }
        return false;
    }
}
