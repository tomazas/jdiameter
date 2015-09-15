/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jdiameter.client.impl;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.EventListener;
import org.jdiameter.api.IllegalDiameterStateException;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.Message;
import org.jdiameter.api.NetworkReqListener;
import org.jdiameter.api.OverloadException;
import org.jdiameter.api.RawSession;
import org.jdiameter.api.Request;
import org.jdiameter.api.RouteException;
import org.jdiameter.client.api.IAssembler;
import org.jdiameter.client.api.IContainer;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.api.IRequest;
import org.jdiameter.client.api.ISession;
import org.jdiameter.client.api.parser.IMessageParser;
import org.jdiameter.common.api.data.ISessionDatasource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation for {@link ISession}
 * 
 * @author erick.svenson@yahoo.com
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 */
public class SessionImpl extends BaseSessionImpl implements ISession {

  private static final Logger logger = LoggerFactory.getLogger(SessionImpl.class);
	
  private Semaphore lock = new Semaphore(1); // container lock

  SessionImpl(IContainer container) {
    setContainer(container);
    try {
      sessionId = container.getSessionFactory().getSessionId();
    }
    catch (IllegalDiameterStateException idse) {
      throw new IllegalStateException("Unable to generate Session-Id", idse);
    }
  }

  void setContainer(IContainer container) {
	try {
		lock.acquire(); // allow container change only if not releasing
	    this.container = container;
	    this.parser = (IMessageParser) container.getAssemblerFacility().getComponentInstance(IMessageParser.class);
	} catch (InterruptedException e) {
		logger.error("failure getting lock", e);
	} finally {
		lock.release();
	}
  }

  public void send(Message message, EventListener<Request, Answer> listener) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException {
    genericSend(message,  listener);
  }

  public void send(Message message, EventListener<Request, Answer> listener, long timeout, TimeUnit timeUnit) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException {
    genericSend(message, listener, timeout, timeUnit);
  }

  public void setRequestListener(NetworkReqListener listener) {
    if (listener != null) {
      super.reqListener = listener;
      container.addSessionListener(sessionId, listener);
    }
  }

  public NetworkReqListener getReqListener() {
    return super.reqListener;
  }

  public Request createRequest(int commandCode, ApplicationId appId, String destRealm) {
    if (isValid) {
      lastAccessedTime = System.currentTimeMillis();
      IRequest m = parser.createEmptyMessage(IRequest.class, commandCode, getAppId(appId));
      m.setNetworkRequest(false);
      m.setRequest(true);
      m.getAvps().addAvp(Avp.SESSION_ID, sessionId, true, false, false);
      appendAppId(appId, m);
      if (destRealm != null) {
        m.getAvps().addAvp(Avp.DESTINATION_REALM, destRealm, true, false, true);
      }
      MessageUtility.addOriginAvps(m, container.getMetaData());
      return m;
    }
    else {
      throw new IllegalStateException("Session already released");
    }
  }

  public Request createRequest(int commandCode, ApplicationId appId, String destRealm, String destHost) {
    if (isValid) {
      lastAccessedTime = System.currentTimeMillis();
      IRequest m = parser.createEmptyMessage(IRequest.class, commandCode, getAppId(appId));
      m.setNetworkRequest(false);
      m.setRequest(true);
      m.getAvps().addAvp(Avp.SESSION_ID, sessionId, true, false, false);
      appendAppId(appId, m);
      if (destRealm != null) {
        m.getAvps().addAvp(Avp.DESTINATION_REALM, destRealm, true, false, true);
      }
      if (destHost != null) {
        m.getAvps().addAvp(Avp.DESTINATION_HOST, destHost, true, false, true);
      }
      MessageUtility.addOriginAvps(m, container.getMetaData());
      return m;
    }
    else {
      throw new IllegalStateException("Session already released");
    }
  }

  public Request createRequest(Request prevRequest) {
    if (isValid) {
      lastAccessedTime = System.currentTimeMillis();
      IRequest request = parser.createEmptyMessage(Request.class, (IMessage) prevRequest);
      request.setRequest(true);
      request.setNetworkRequest(false);
      MessageUtility.addOriginAvps(request, container.getMetaData());
      return request;
    }
    else {
      throw new IllegalStateException("Session already released");
    }
  }

  public void release() {
    isValid = false;
    
    try {
    	lock.acquire(); // prevent container NullPointerException
    	
	    if (container != null) {
	      container.removeSessionListener(sessionId);
	      IAssembler assembler = container.getAssemblerFacility();
	      ISessionDatasource datasource = assembler.getComponentInstance(ISessionDatasource.class);
	      datasource.removeSession(sessionId);
	    }
	    
	    container = null;
	    parser = null;
	    reqListener = null;
    } catch (InterruptedException e) {
		logger.error("failure getting lock", e);
	} finally {
		lock.release();
	}
  }

  public boolean isWrapperFor(Class<?> iface) throws InternalException {
    return iface == RawSession.class;
  }

  @SuppressWarnings("unchecked")
  public <T> T unwrap(Class<T> iface) throws InternalException {
    return (T) (iface == RawSession.class ?  new RawSessionImpl(container) : null);
  }

}
