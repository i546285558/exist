/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2003-2010 The eXist Project
 *  http://exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *
 *  $Id$
 */
package org.exist.security;

import org.apache.log4j.Logger;
import org.exist.EXistException;
import org.exist.security.internal.Password;
import org.exist.security.internal.aider.UserAider;
import org.exist.security.ldap.LDAPbindSecurityManager;
import org.exist.security.realm.Realm;
import org.exist.storage.BrokerPool;
import org.exist.util.DatabaseConfigurationException;
import org.exist.xmldb.XmldbURI;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

/**
 * Represents a user within the database.
 * 
 * @author Wolfgang Meier <wolfgang@exist-db.org> Modified by {Marco.Tampucci,
 *         Massimo.Martinelli} @isti.cnr.it
 */
public class UserImpl implements User {

	private final static Logger LOG = Logger.getLogger(UserImpl.class);

	private final static String GROUP = "group";
	private final static String NAME = "name";
	private final static String PASS = "password";
	private final static String DIGEST_PASS = "digest-password";
	private final static String USER_ID = "uid";
	private final static String HOME = "home";

	public static int PASSWORD_ENCODING;
	public static boolean CHECK_PASSWORDS = true;

	static {
		Properties props = new Properties();
		try {
			props.load(UserImpl.class.getClassLoader().getResourceAsStream(
					"org/exist/security/security.properties"));
		} catch (IOException e) {
		}
		String option = props.getProperty("passwords.encoding", "md5");
		setPasswordEncoding(option);
		option = props.getProperty("passwords.check", "yes");
		CHECK_PASSWORDS = option.equalsIgnoreCase("yes")
				|| option.equalsIgnoreCase("true");
	}

	static public void enablePasswordChecks(boolean check) {
		CHECK_PASSWORDS = check;
	}

	static public void setPasswordEncoding(String encoding) {
		if (encoding != null) {
			LOG.equals("Setting password encoding to " + encoding);
			if (encoding.equalsIgnoreCase("plain")) {
				PASSWORD_ENCODING = PLAIN_ENCODING;
			} else if (encoding.equalsIgnoreCase("md5")) {
				PASSWORD_ENCODING = MD5_ENCODING;
			} else {
				PASSWORD_ENCODING = SIMPLE_MD5_ENCODING;
			}
		}
	}

	static public User getUserFromServletRequest(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal instanceof User) {
			return (User) principal;
			
		//workaroud strange jetty authentication method, why encapsulate user object??? -shabanovd 
        } else if (principal != null && "org.eclipse.jetty.plus.jaas.JAASUserPrincipal".equals(principal.getClass().getName()) ) {
        	try {
        		Method method = principal.getClass().getMethod("getSubject");
        		Object obj = method.invoke(principal);
				if (obj instanceof javax.security.auth.Subject) {
					javax.security.auth.Subject subject = (javax.security.auth.Subject) obj;
					for (Principal _principal_ : subject.getPrincipals()) {
				        if (_principal_ instanceof User) {
							return (User) _principal_;
				        }
					}
				}
			} catch (SecurityException e) {
			} catch (IllegalArgumentException e) {
			} catch (IllegalAccessException e) {
			} catch (NoSuchMethodException e) {
			} catch (InvocationTargetException e) {
			}
		}
        
        return null;
	}
	
	private Realm realm;

	private String name;
	
	private int uid = -1;
	private XmldbURI home = null;

	private Group defaultRole = null;
	private Set<Group> roles = null;
	
	private String password = null;
	private String digestPassword = null;

	/**
	 * Indicates if the user belongs to the dba group, i.e. is a superuser.
	 */
	private boolean hasDbaRole = false;

	/**
	 * Create a new user with name and password
	 * 
	 *@param user
	 *            Description of the Parameter
	 *@param password
	 *            Description of the Parameter
	 */
	public UserImpl(Realm realm, String name, String password) {
		this.realm = realm;
		this.name = name;
		setPassword(password);
	}

	public UserImpl(Realm realm, int id, String name, String password) {
		this(realm, name, password);
		this.uid = id;
	}

	/**
	 * Create a new user with name
	 * 
	 * @param user
	 *            Description of the Parameter
	 */
	public UserImpl(Realm realm, String name) {
		this.realm = realm;
		this.name = name;
	}

	/**
	 * Create a new user with name, password and primary group
	 * 
	 *@param user
	 *            Description of the Parameter
	 *@param password
	 *            Description of the Parameter
	 *@param primaryGroup
	 *            Description of the Parameter
	 */
	public UserImpl(Realm realm, String name, String password, String primaryGroup) {
		this(realm, name, password);
		defaultRole = addGroup(primaryGroup);
	}

	/**
	 * Read a new user from the given DOM node
	 * 
	 *@param node
	 *            Description of the Parameter
	 *@exception DatabaseConfigurationException
	 *                Description of the Exception
	 */
	public UserImpl(Realm realm, int majorVersion, int minorVersion, Element node)
			throws DatabaseConfigurationException {
		
		this.realm = realm;
		
		name = node.getAttribute(NAME);
		if (name == null ) //|| name.length() == 0
			throw new DatabaseConfigurationException("user needs a name");
		Attr attr;
		if (majorVersion == 0) {
			attr = node.getAttributeNode(PASS);
			this.digestPassword = attr == null ? null : attr.getValue();
			setPassword(null);
//			this.password = null;
		} else {
			attr = node.getAttributeNode(PASS);
			String password = attr == null ? null : attr.getValue();
			this.setPassword(password);
			if (password != null && password.length() > 0) {
				if (password.startsWith("{MD5}")) {
					this.password = password.substring(5);
				}
				if (this.password.charAt(0) == '{') {
					throw new DatabaseConfigurationException(
							"Unrecognized password encoding " + password
									+ " for user " + name);
				}
			}
			attr = node.getAttributeNode(DIGEST_PASS);
			this.digestPassword = attr == null ? null : attr.getValue();
		}
		Attr userId = node.getAttributeNode(USER_ID);
		if (userId == null)
			throw new DatabaseConfigurationException("attribute id missing");
		try {
			uid = Integer.parseInt(userId.getValue());
		} catch (NumberFormatException e) {
			throw new DatabaseConfigurationException("illegal user id: "
					+ userId + " for user " + name);
		}
		Attr homeAttr = node.getAttributeNode(HOME);
		this.home = homeAttr == null ? null : XmldbURI.create(homeAttr.getValue());
		NodeList gl = node.getChildNodes();
		Node group;
		for (int i = 0; i < gl.getLength(); i++) {
			group = gl.item(i);
			if (group.getNodeType() == Node.ELEMENT_NODE
					&& group.getLocalName().equals(GROUP))
				addGroup(group.getFirstChild().getNodeValue());
		}
		
		//TODO: workaround for 'null' admin's password. It should be removed after 6 months (@ 10 July 2010)
		if (uid == 1 && password == null) setPassword("");
		
	}
	
    public UserImpl(Realm realm, int id, User from_user) {
        
    	this.realm = realm;

        uid = id;

        name = from_user.getName();
        home = from_user.getHome();
        
        defaultRole = from_user.getDefaultGroup();

        if (from_user instanceof UserImpl) {
			UserImpl user = (UserImpl) from_user;

	        defaultRole = user.defaultRole;
	        roles = user.roles;

	        password = user.password;
	        digestPassword = user.digestPassword;
	        
	        hasDbaRole = user.hasDbaRole;

	        _cred = user._cred;
		} else if (from_user instanceof UserAider) {
			UserAider user = (UserAider) from_user;
			
			String[] gl = user.getGroups();
			for (int i = 0; i < gl.length; i++) {
				addGroup(gl[i]);
			}

	        setPassword(user.getPassword());
	        digestPassword = user.getDigestPassword();
		}

    }

	public UserImpl(Realm realm, UserImpl from_user, Object credentials) {
        uid = from_user.uid;
        name = from_user.name;
        home = from_user.home;
        
        defaultRole = from_user.defaultRole;
        roles = from_user.roles;

        password = from_user.password;
        digestPassword = from_user.digestPassword;
        
        hasDbaRole = from_user.hasDbaRole;

        _cred = from_user._cred;

        this.realm = realm;
        
        authenticate(credentials);
    }

    /*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#addGroup(java.lang.String)
	 */
	public final Group addGroup(String name) {
		Group role = realm.getRole(name);
		if (role == null)
			return null;
		
		if (roles == null) {
			roles = new HashSet<Group>();
		}
		
		roles.add(role);
		
		if (SecurityManager.DBA_GROUP.equals(name))
			hasDbaRole = true;
		
		return role;
	}

	public final Group addGroup(Group group) {
		return addGroup(group.getName());
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#remGroup(java.lang.String)
	 */
	public final void remGroup(String group) {
		for (Group role : roles) {
			if (role.getName().equals(group)) {
				roles.remove(role);
				break;
			}
		}

		if (SecurityManager.DBA_GROUP.equals(group))
			hasDbaRole = false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#setGroups(java.lang.String[])
	 */
	public final void setGroups(String[] groups) {
//		this.groups = groups;
//		for (int i = 0; i < groups.length; i++)
//			if (SecurityManager.DBA_GROUP.equals(groups[i]))
//				hasDbaRole = true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#getGroups()
	 */
	public final String[] getGroups() {
		if (roles == null) return new String[0];
		
		int i = 0;
		String[] names = new String[roles.size()];
		for (Group role : roles) {
			names[i] = role.getName();
			i++;
		}
		
		return names;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#hasDbaRole()
	 */
	public final boolean hasDbaRole() {
		return hasDbaRole;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#getName()
	 */
	public final String getName() {
		return name;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#getUID()
	 */
	public final int getUID() {
		return uid;
	}

	/**
	 * Get the user's password
	 * 
	 * @return Description of the Return Value
	 * @deprecated
	 */
	public final String getPassword() {
		return password;
	}

	@Deprecated
	public final String getDigestPassword() {
		return digestPassword;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#getPrimaryGroup()
	 */
	public final String getPrimaryGroup() {
		if (defaultRole == null) {
			if (roles == null || roles.size() == 0)
				return null;
			
			return ((Group) roles.toArray()[0]).getName();
		}
			
		return defaultRole.getName();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#hasGroup(java.lang.String)
	 */
	public final boolean hasGroup(String group) {
		if (roles == null)
			return false;
		
		for (Group role : roles) {
			if (role.getName().equals(group))
				return true;
		}
		
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#setPassword(java.lang.String)
	 */
	public final void setPassword(String passwd) {
    	_cred = new Password(passwd);

		if (passwd == null) {
			this.password = null;
			this.digestPassword = null;
		} else {
			this.password = ((Password)_cred).pw;
			this.digestPassword = digest(passwd);
		}
	}

	/**
	 * Sets the digest passwod value of the User object
	 * 
	 * @param passwd
	 *            The new passwordDigest value
	 * @deprecated
	 */
	public final void setPasswordDigest(String passwd) {
		//setPassword(passwd);
		this.digestPassword = (passwd == null) ? null : passwd;
	}

	/**
	 * Sets the encoded passwod value of the User object
	 * 
	 * @param passwd
	 *            The new passwordDigest value
	 * @deprecated
	 */
	public final void setEncodedPassword(String passwd) {
		setPassword("{MD5}"+passwd);
		this.password = (passwd == null) ? null : passwd;
	}

	public final String digest(String passwd) {
		switch (PASSWORD_ENCODING) {
		case PLAIN_ENCODING:
			return passwd;
		case MD5_ENCODING:
			return MessageDigester
					.md5(name + ":" + realm.getId() + ":" + passwd, false);
		default:
			return MessageDigester.md5(passwd, true);
		}

	}

	public final String toString() {
		StringBuffer buf = new StringBuffer();
		buf.append("<user name=\"");
		buf.append(name);
		buf.append("\" ");
		buf.append("uid=\"");
		buf.append(Integer.toString(uid));
		buf.append("\"");
		if (password != null) {
			buf.append(" password=\"{MD5}");
			buf.append(password);
			buf.append('"');
		}
		if (digestPassword != null) {
			buf.append(" digest-password=\"");
			buf.append(digestPassword);
			buf.append('"');
		}
		if (home != null) {
			buf.append(" home=\"");
			buf.append(home);
			buf.append("\">");
		} else
			buf.append(">");
		if (roles != null) {
			for (Group role : roles) {
				buf.append("<group>");
				buf.append(role.getName());
				buf.append("</group>");
			}
		}
		buf.append("</user>");
		return buf.toString();
	}

	/**
	 * Split up the validate method into two, to make it possible to
	 * authenticate users, which are not defined in the instance named "exist"
	 * without having impact on the standard functionality.
	 * 
	 * @param passwd
	 * @return true if the password was correct, false if not, or if there was a
	 *         problem.
	 */
	@Deprecated //use SecurityManager.authenticate
	public final boolean validate(String passwd) {
		SecurityManager sm;
		try {
			sm = BrokerPool.getInstance().getSecurityManager();
			return validate(passwd, sm);
		} catch (EXistException e) {
			LOG.warn("Failed to get security manager in validate: ", e);
			return false;
		}
	}

	@Deprecated //use SecurityManager.authenticate
	public final boolean validate(String passwd, SecurityManager sm) {
		// security management is disabled if in repair mode
		if (!CHECK_PASSWORDS)
			return true;

		if (password == null && digestPassword == null) {
			return true;
		} else if (uid == 1 && passwd == null) {
			passwd = "";
		}
		if (passwd == null) {
			return false;
		}

		// [ 1557095 ] LDAP passwords patch
		// Try to authenticate using LDAP
		if (sm != null) {
			if (sm instanceof LDAPbindSecurityManager) {
				if (((LDAPbindSecurityManager) sm).bind(name, passwd))
					return true;
				else
					return false;
			}
		}

		if (password != null) {
			if (MessageDigester.md5(passwd, true).equals(password)) {
				return true;
			}
		}
		if (digestPassword != null) {
			if (digest(passwd).equals(digestPassword)) {
				return true;
			}
		}
		return false;
	}

	@Deprecated //use SecurityManager.authenticate
	public final boolean validateDigest(String passwd) {
		if (digestPassword == null)
			return true;
		if (passwd == null)
			return false;
		return digest(passwd).equals(digestPassword);
	}

	//switch to protected
	public void setUID(int uid) {
		this.uid = uid;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#setHome(org.exist.xmldb.XmldbURI)
	 */
	public void setHome(XmldbURI homeCollection) {
		home = homeCollection;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#getHome()
	 */
	public XmldbURI getHome() {
		return home;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	public boolean equals(Object obj) {
		UserImpl other = (UserImpl) obj;

		if (other != null) {
			return uid == other.uid;
		} else {
			return (false);
		}
	}

    private Credential _cred;

    @Override
	public boolean authenticate(Object credentials) {
    	authenticated = _cred!=null && _cred.check(credentials);
		return authenticated;
	}

	@Override
	public Realm getRealm() {
		return realm;
	}

	private boolean authenticated = false;
	
	@Override
	public boolean isAuthenticated() {
		return authenticated;
	}

	private Map<String, Object> attributes = new HashMap<String, Object>();

    /**
     * Add a named attribute.
     *
     * @param name
     * @param value
     */
	@Override
	public void setAttribute(String name, Object value) {
		attributes.put(name, value);
	}

    /**
     * Get the named attribute value.
     *
     * @param name The String that is the name of the attribute.
     * @return The value associated with the name or null if no value is associated with the name.
     */
	@Override
	public Object getAttribute(String name) {
		return attributes.get(name);
	}

    /**
     * Returns the set of attributes names.
     *
     * @return the Set of attribute names.
     */
    @Override
    public Set<String> getAttributeNames() {
        return attributes.keySet();
    }

	@Override
	public Group getDefaultGroup() {
		return defaultRole;
	}
}