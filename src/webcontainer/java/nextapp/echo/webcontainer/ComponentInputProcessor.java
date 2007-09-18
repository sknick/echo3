/* 
 * This file is part of the Echo Web Application Framework (hereinafter "Echo").
 * Copyright (C) 2002-2005 NextApp, Inc.
 *
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 */

package nextapp.echo.webcontainer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import nextapp.echo.app.Component;
import nextapp.echo.app.serial.PropertyPeerFactory;
import nextapp.echo.app.serial.SerialException;
import nextapp.echo.app.serial.SerialPropertyPeer;
import nextapp.echo.app.update.UpdateManager;
import nextapp.echo.app.util.Context;
import nextapp.echo.app.util.DomUtil;

import org.w3c.dom.Element;

/**
 * <code>ClientMessage.Processor</code> which de-serializes
 * changed properties and fired events generated by the
 * client-side component hierarchy and passes them
 * to appropriate <code>ComponentSynchronizePeer</code>s
 * for processing.
 */
public class ComponentInputProcessor
implements ClientMessage.Processor {
    
    private Element eventElement;
    private String eventType;
    private String eventComponentId;
    private Map componentUpdateMap = new HashMap();
    
    /**
     * Parses the component synchronize directive element, storing
     * necessary values in instance variables for later processing.
     * 
     * @param dirElement the "dir" element to parse
     */
    private void parseDirElement(Element dirElement) {
        // Retrieve event.
        eventElement = DomUtil.getChildElementByTagName(dirElement, "e");
        if (eventElement != null) {
            eventType = eventElement.getAttribute("t");
            eventComponentId = eventElement.getAttribute("i");
        }
        
        // Retrieve property updates.
        Element[] pElements = DomUtil.getChildElementsByTagName(dirElement, "p");
        for (int i = 0; i < pElements.length; ++i) {
            String componentId = pElements[i].getAttribute("i");
            String propertyName = pElements[i].getAttribute("n");
        
            Map propertyMap = (Map) componentUpdateMap.get(componentId);
            if (propertyMap == null) {
                propertyMap = new HashMap();
                componentUpdateMap.put(componentId, propertyMap);
            }
            
            propertyMap.put(propertyName, pElements[i]);
        }
    }
    
    /**
     * @see nextapp.echo.webcontainer.ClientMessage.Processor#process(nextapp.echo.app.util.Context, org.w3c.dom.Element)
     */
    public void process(Context context, Element dirElement) 
    throws IOException {
        parseDirElement(dirElement);
        
        UserInstance userInstance = (UserInstance) context.get(UserInstance.class);
        PropertyPeerFactory propertyPeerFactory = (PropertyPeerFactory) context.get(PropertyPeerFactory.class);
        UpdateManager updateManager = userInstance.getApplicationInstance().getUpdateManager();

        Iterator updatedComponentIdIt  = getUpdatedComponentIds();
        while (updatedComponentIdIt.hasNext()) {
            String componentId = (String) updatedComponentIdIt.next();
            Component component = userInstance.getComponentByClientRenderId(componentId);
            ComponentSynchronizePeer componentPeer = SynchronizePeerFactory.getPeerForComponent(component.getClass());
            
            Iterator updatedPropertyIt = getUpdatedPropertyNames(componentId);
            while (updatedPropertyIt.hasNext()) {
                String propertyName = (String) updatedPropertyIt.next();
                Element propertyElement = getUpdatedProperty(componentId, propertyName);

                Class propertyClass = componentPeer.getInputPropertyClass(propertyName);
                if (propertyClass == null) {
                    //FIXME. add ex handling.
                    System.err.println("Could not determine class of property: " + propertyName);
                    continue;
                }
                
                SerialPropertyPeer propertyPeer = propertyPeerFactory.getPeerForProperty(propertyClass);
                
                if (propertyPeer == null) {
                    //FIXME. add ex handling.
                    System.err.println("No peer available for property: " + propertyName + " of class: " + propertyClass);
                    continue;
                }
                
                try {
                    Object propertyValue = propertyPeer.toProperty(context, component.getClass(), propertyElement);
                    componentPeer.storeInputProperty(context, component, propertyName, -1, propertyValue);
                } catch (SerialException ex) {
                    //FIXME. bad ex handling.
                    throw new IOException(ex.toString());
                }
            }
        }
        
        if (getEvent() != null) {
            Component component = userInstance.getComponentByClientRenderId(getEventComponentId());
            ComponentSynchronizePeer componentPeer = SynchronizePeerFactory.getPeerForComponent(component.getClass());
            Class eventDataClass = componentPeer.getEventDataClass(getEventType());
            if (eventDataClass == null) {
                componentPeer.processEvent(context, component, getEventType(), null);
            } else {
                SerialPropertyPeer propertyPeer = propertyPeerFactory.getPeerForProperty(eventDataClass);
                if (propertyPeer == null) {
                    //FIXME. add ex handling.
                    System.err.println("No peer available for event data for event type: " + getEventType() 
                            + " of class: " + eventDataClass);
                }
                try {
                    Object eventData = propertyPeer.toProperty(context, component.getClass(), getEvent());
                    componentPeer.processEvent(context, component, getEventType(), eventData);
                } catch (SerialException ex) {
                    //FIXME. bad ex handling.
                    throw new IOException(ex.toString());
                }
            }
        }

        updateManager.processClientUpdates();
        
    }

    /**
     * Returns the ids of all updated components.
     * 
     * @return the ids of all updated components.
     */
    private Iterator getUpdatedComponentIds() {
        return componentUpdateMap.keySet().iterator(); 
    }
    
    /**
     * Returns the names of updated properties for a specific component.
     * 
     * @param componentId the id of the component
     * @return the updated property names
     */
    private Iterator getUpdatedPropertyNames(String componentId) {
        Map propertyMap = (Map) componentUpdateMap.get(componentId);
        return propertyMap.keySet().iterator();
    }
    
    /**
     * Returns the "p" element of a specific updated property of a specific component
     * 
     * @param componentId the id of the component
     * @param propertyName the name of the property
     * @return the property element
     */
    private Element getUpdatedProperty(String componentId, String propertyName) {
        Map propertyMap = (Map) componentUpdateMap.get(componentId);
        return (Element) propertyMap.get(propertyName);
    }
    
    /**
     * Returns the event element of the event that resulted in the client-server interaction.
     * 
     * @return the event element
     */
    private Element getEvent() {
        return eventElement;
    }
    
    /**
     * Returns the event type.
     * 
     * @return the event type
     */
    private String getEventType() {
        return eventType;
    }
    
    /**
     * Returns the id of the component that fired the event that resulted in the client-server interaction.
     * 
     * @return the event component id
     */
    private String getEventComponentId() {
        return eventComponentId;
    }
}
