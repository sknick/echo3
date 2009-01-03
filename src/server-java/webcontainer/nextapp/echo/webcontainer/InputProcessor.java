/* 
 * This file is part of the Echo Web Application Framework (hereinafter "Echo").
 * Copyright (C) 2002-2009 NextApp, Inc.
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

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import nextapp.echo.app.serial.PropertyPeerFactory;
import nextapp.echo.app.serial.SerialContext;
import nextapp.echo.app.update.ClientUpdateManager;
import nextapp.echo.app.update.UpdateManager;
import nextapp.echo.app.util.Context;
import nextapp.echo.app.util.DomUtil;
import nextapp.echo.app.util.Log;
import nextapp.echo.webcontainer.util.XmlRequestParser;

/**
 * Parses an XML <code>ClientMessage</code> describing client-side changes to the
 * state of an application that is sent to the remote client as the request
 * of a synchronization HTTP connection.
 */
public class InputProcessor {
    
    static {
        ClientMessage.register("CSync", ComponentInputProcessor.class);
        ClientMessage.register("ClientProperties", ClientPropertiesInputProcessor.class);
        ClientMessage.register("CFocus", ComponentFocusInputProcessor.class);
    }
    
    /**
     * <code>Context</code> implementation.
     */
    private class InputContext implements Context {
        
        /**
         * <code>SerialContext</code> implementation.
         */
        private SerialContext serialContext = new SerialContext() {
        
            /** The <code>ClassLoader</code> for this context. */
            private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            /**
             * @see nextapp.echo.app.serial.SerialContext#getClassLoader()
             */
            public ClassLoader getClassLoader() {
                return classLoader;
            }
        
            /**
             * @see nextapp.echo.app.serial.SerialContext#getDocument()
             */
            public Document getDocument() {
                return clientMessage.getDocument();
            }

            /**
             * @see nextapp.echo.app.serial.SerialContext#getFlags()
             */
            public int getFlags() {
                return 0;
            }
        };
        
        /**
         * @see nextapp.echo.app.util.Context#get(java.lang.Class)
         */
        public Object get(Class specificContextClass) {
            if (specificContextClass == SerialContext.class) {
                return serialContext;
            } else if (specificContextClass == Connection.class) {
                return conn;
            } else if (specificContextClass == PropertyPeerFactory.class) {
                return propertyPeerFactory;
            } else if (specificContextClass == UserInstance.class) {
                return conn.getUserInstance();
            } else if (specificContextClass == ClientMessage.class) {
                return clientMessage;
            } else if (specificContextClass == ClientUpdateManager.class) {
                return conn.getUserInstance().getApplicationInstance().getUpdateManager().getClientUpdateManager();
            } else {
                return null;
            }
        }
    }
    
    /** The <code>Connection</code> being processed. */
    private Connection conn;
    
    /** The <code>SynchronizationState</code> used to determine if this client/server are out-of-sync. */
    private SynchronizationState syncState;
    
    /** The incoming <code>ClientMessage</code> provided to the context. */
    private ClientMessage clientMessage;
    
    /** The <code>PropertyPeerFactory</code> provided to the context. */
    private PropertyPeerFactory propertyPeerFactory;

    /**
     * Creates a new <code>InputProcessor</code>.
     * 
     * @param syncState the <code>SynchronizationState</code> of the current synchronization
     * @param conn the <code>Connection</code> for which the input is being parsed
     */
    public InputProcessor(SynchronizationState syncState, Connection conn) {
        super();
        this.syncState = syncState;
        this.conn = conn;
        propertyPeerFactory = PropertySerialPeerFactory.INSTANCE; //FIXME temporary
    }
    
    /**
     * Processes input to the application, parsing a client message provided in the <code>Connection</code>.
     * Verifies client/server are in sync, and performs full refresh if they are not.
     * Writes incoming XML message to <code>System.err</code> in the event debug flag is enabled.
     * Invokes <code>ClientMessage.process()</code> to begin client-message processing (assuming client/server are synchronized).
     */
    public void process() 
    throws IOException {
        Document document = XmlRequestParser.parse(conn.getRequest(), conn.getUserInstance().getCharacterEncoding());        
        clientMessage = new ClientMessage(document);
        UserInstance userInstance = conn.getUserInstance();
        UpdateManager updateManager = userInstance.getUpdateManager();
        Context context = new InputContext();
        
        if (ClientMessage.TYPE_INITIALIZE.equals(clientMessage.getType())) {
            // Flag full refresh if initializing.
            updateManager.getServerUpdateManager().processFullRefresh();
        } else if (clientMessage.getTransactionId() != userInstance.getCurrentTransactionId()) {
            // Flag full refresh for an out of sync client.
            updateManager.getServerUpdateManager().processFullRefresh();
            this.syncState.setOutOfSync();
            if (WebContainerServlet.DEBUG_PRINT_MESSAGES_TO_CONSOLE) {
                Log.log("Client out of sync: client id = " + clientMessage.getTransactionId() + 
                        ", server id = " + userInstance.getCurrentTransactionId());
            }
        }
        
        if (WebContainerServlet.DEBUG_PRINT_MESSAGES_TO_CONSOLE) {
            // Print ClientMessage to console. 
            try {
                DomUtil.save(clientMessage.getDocument(), System.err, DomUtil.OUTPUT_PROPERTIES_INDENT);
            } catch (SAXException ex) {
                throw new SynchronizationException("Cannot render XML sync message to console.", ex);
            }
        }
        
        if (!syncState.isOutOfSync()) {
            // Only process the client message if client/server are synchronized.
            clientMessage.process(context);
        }
    }
}
