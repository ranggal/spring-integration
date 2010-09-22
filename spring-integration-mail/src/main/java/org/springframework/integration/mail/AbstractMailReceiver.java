/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.mail;

import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.URLName;
import javax.mail.internet.MimeMessage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.integration.context.IntegrationObjectSupport;
import org.springframework.util.Assert;

/**
 * Base class for {@link MailReceiver} implementations.
 * 
 * @author Arjen Poutsma
 * @author Jonas Partner
 * @author Mark Fisher
 * @author Iwein Fuld
 * @author Oleg Zhurakousky
 */
public abstract class AbstractMailReceiver extends IntegrationObjectSupport implements MailReceiver, DisposableBean{

	protected final Log logger = LogFactory.getLog(this.getClass());

	private final URLName url;

	private volatile String protocol;

	private volatile int maxFetchSize = -1;

	private volatile Session session;

	private volatile Store store;

	private volatile Folder folder;

	private volatile boolean shouldDeleteMessages = false;
	
	private volatile int folderOpenMode = Folder.READ_ONLY;

	private volatile Properties javaMailProperties = new Properties();

	protected volatile boolean initialized;

	private final Object initializationMonitor = new Object();

	private Authenticator javaMailAuthenticator;


	public AbstractMailReceiver() {
		this.url = null;
	}

	public AbstractMailReceiver(URLName urlName) {
		Assert.notNull(urlName, "urlName must not be null");
		this.url = urlName;
		this.shouldDeleteMessages = urlName.getProtocol().startsWith("pop3");
	}

	public AbstractMailReceiver(String url) {
		if (url != null) {
			this.url = new URLName(url);
			this.shouldDeleteMessages = this.url.getProtocol().startsWith("pop3");
		}
		else {
			this.url = null;
		}
	}


	public void setProtocol(String protocol) {
		if (this.url != null) {
			Assert.isTrue(this.url.getProtocol().equals(protocol),
					"The 'protocol' does not match that provided by the Store URI.");
		}
		this.protocol = protocol;
	}

	/**
	 * Set the {@link Session}. Otherwise, the Session will be created by invocation of
	 * {@link Session#getInstance(Properties)} or {@link Session#getInstance(Properties, Authenticator)}.
	 * 
	 * @see #setJavaMailProperties(Properties)
	 * @see #setJavaMailAuthenticator(Authenticator)
	 */
	public void setSession(Session session) {
		Assert.notNull(session, "Session must not be null");
		this.session = session;
	}

	/**
	 * A new {@link Session} will be created with these properties (and the JavaMailAuthenticator if provided).
	 * Use either this method or {@link #setSession}, but not both.
	 * 
	 * @see #setJavaMailAuthenticator(Authenticator)
	 * @see #setSession(Session)
	 */
	public void setJavaMailProperties(Properties javaMailProperties) {
		this.javaMailProperties = javaMailProperties;
	}

	/**
	 * Optional, sets the Authenticator to be used to obtain a session. This will not be used if
	 * {@link AbstractMailReceiver#setSession} has been used to configure the {@link Session} directly.
	 * 
	 * @see #setSession(Session)
	 */
	public void setJavaMailAuthenticator(Authenticator javaMailAuthenticator) {
		this.javaMailAuthenticator = javaMailAuthenticator;
	}

	/**
	 * Specify the maximum number of Messages to fetch per call to {@link #receive()}.
	 */
	public void setMaxFetchSize(int maxFetchSize) {
		this.maxFetchSize = maxFetchSize;
	}

	/**
	 * Specify whether mail messages should be deleted after retrieval.
	 */
	public void setShouldDeleteMessages(boolean shouldDeleteMessages) {
		this.shouldDeleteMessages = shouldDeleteMessages;
	}
	/**
	 * Indicates whether the mail messages should be deleted after being received.
	 */
	protected boolean shouldDeleteMessages() {
		return this.shouldDeleteMessages;
	}

	protected Folder getFolder() {
		return this.folder;
	}

	/**
	 * Subclasses must implement this method to return new mail messages.
	 */
	protected abstract Message[] searchForNewMessages() throws MessagingException;

	private void openSession() throws MessagingException {
		if (this.session == null) {
			if (this.javaMailAuthenticator != null) {
				this.session = Session.getInstance(this.javaMailProperties, this.javaMailAuthenticator);
			}
			else {
				this.session = Session.getInstance(this.javaMailProperties);
			}
		}
		if (this.store == null) {
			if (this.url != null) {
				this.store = this.session.getStore(this.url);
			}
			else if (this.protocol != null) {
				this.store = this.session.getStore(this.protocol);
			}
			else {
				this.store = this.session.getStore();
			}
		}
		if (!this.store.isConnected()) {
			if (logger.isDebugEnabled()) {
				logger.debug("connecting to store [" + MailTransportUtils.toPasswordProtectedString(this.url) + "]");
			}
			this.store.connect();
		}
	}

	protected void openFolder() throws MessagingException {
		this.openSession();
		if (this.folder == null) {
			this.folder = this.store.getFolder(this.url);
		}
		if (this.folder == null || !this.folder.exists()) {
			throw new IllegalStateException("no such folder [" + this.url.getFile() + "]");
		}
		if (this.folder.isOpen()) {
			return;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("opening folder [" + MailTransportUtils.toPasswordProtectedString(this.url) + "]");
		}
		this.folder.open(folderOpenMode);
	}
	
	public synchronized Message[] receive() {
		try {
			this.openFolder();
			if (logger.isInfoEnabled()) {
				logger.info("attempting to receive mail from folder [" + this.getFolder().getFullName() + "]");
			}
			Message[] messages = this.searchForNewMessages();
			if (this.maxFetchSize > 0 && messages.length > this.maxFetchSize) {
				Message[] reducedMessages = new Message[this.maxFetchSize];
				System.arraycopy(messages, 0, reducedMessages, 0, this.maxFetchSize);
				messages = reducedMessages;
			}
			if (logger.isDebugEnabled()) {
				logger.debug("found " + messages.length + " new messages");
			}
			if (messages.length > 0) {
				this.fetchMessages(messages);
			}

			Message[] copiedMessages = new Message[messages.length];
			for (int i = 0; i < messages.length; i++) {
				this.setAdditionalFlags(messages[i]);
				copiedMessages[i] = new MimeMessage((MimeMessage) messages[i]);
			}
			if (this.shouldDeleteMessages()) {
				this.deleteMessages(messages);
			}
			return copiedMessages;
		}
		catch (Exception e) {
			throw new org.springframework.integration.MessagingException(
					"failure occurred while receiving from folder", e);
		}
		finally {
			MailTransportUtils.closeFolder(this.folder);
		}
	}

	/**
	 * Fetches the specified messages from this receiver's folder. Default
	 * implementation {@link Folder#fetch(Message[], FetchProfile) fetches}
	 * every {@link javax.mail.FetchProfile.Item}.
	 * 
	 * @param messages the messages to fetch
	 * @throws MessagingException in case of JavaMail errors
	 */
	protected void fetchMessages(Message[] messages) throws MessagingException {
		FetchProfile contentsProfile = new FetchProfile();
		contentsProfile.add(FetchProfile.Item.ENVELOPE);
		contentsProfile.add(FetchProfile.Item.CONTENT_INFO);
		contentsProfile.add(FetchProfile.Item.FLAGS);
		this.folder.fetch(messages, contentsProfile);
	}

	/**
	 * Deletes the given messages from this receiver's folder.
	 * 
	 * @param messages the messages to delete
	 * @throws MessagingException in case of JavaMail errors
	 */
	protected void deleteMessages(Message[] messages) throws MessagingException {
		for (int i = 0; i < messages.length; i++) {
			messages[i].setFlag(Flags.Flag.DELETED, true);
		}
	}

	public void destroy() throws Exception {
		synchronized (this.initializationMonitor) {
			MailTransportUtils.closeFolder(this.folder);
			MailTransportUtils.closeService(this.store);
			this.folder = null;
			this.store = null;
			this.initialized = false;
		}
	}

	@Override
	public String toString() {
		return this.url.toString();
	}
	
	protected void setAdditionalFlags(Message message) throws MessagingException {
		// nothing at the base class
	}
	
	void setFolderOpenMode(int folderOpenMode) {
		this.folderOpenMode = folderOpenMode;
	}
}
