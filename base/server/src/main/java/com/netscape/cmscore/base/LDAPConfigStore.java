// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2007, 2014 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---

package com.netscape.cmscore.base;

import java.io.ByteArrayOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.IConfigStore;
import com.netscape.certsrv.ldap.ELdapException;
import com.netscape.cmsutil.ldap.LDAPPostReadControl;
import com.netscape.cmsutil.ldap.LDAPUtil;

import netscape.ldap.LDAPAttribute;
import netscape.ldap.LDAPAttributeSet;
import netscape.ldap.LDAPConnection;
import netscape.ldap.LDAPConstraints;
import netscape.ldap.LDAPControl;
import netscape.ldap.LDAPEntry;
import netscape.ldap.LDAPException;
import netscape.ldap.LDAPModification;

/**
 * LDAPConfigStore:
 * Extends PropConfigStore with methods to load/save from/to file for
 * persistent storage. This is a configuration store agent who
 * reads data from an LDAP entry.
 * <P>
 *
 * @version $Revision$, $Date$
 * @see PropConfigStore
 */
public class LDAPConfigStore extends ConfigStorage {

    public static Logger logger = LoggerFactory.getLogger(LDAPConfigStore.class);

    private LDAPConnection conn;
    private String dn;
    private String attr;
    private LDAPAttribute[] createAttrs;

    /**
     * Constructs an LDAP configuration store.
     * <P>
     *
     * @param conn Database connection
     * @param dn Distinguished name of record containing config store
     * @param attr Name of attribute containing config store
     * @param createAttrs Set of initial attributes if creating the entry.  Should
     *              contain cn, objectclass and possibly other attributes.
     */
    public LDAPConfigStore(
        LDAPConnection conn,
        String dn, LDAPAttribute[] createAttrs, String attr
    ) throws Exception {
        this.conn = conn;
        this.dn = dn;
        this.createAttrs = createAttrs;
        this.attr = attr;
    }

    /**
     * Commit the configuration to the database.
     *
     * All uses of LDAPProfileStore at time of writing call with
     * createBackup=false, so the argument is ignored.
     *
     * If backup becomes necessary, the constructor should be
     * modified to take a String backupAttr, and the existing
     * content be copied to that attribute.
     *
     * @param createBackup Ignored.
     */
    public void commit(IConfigStore config, boolean createBackup) throws EBaseException {
        String[] attrs = {};
        commitReturn(config, createBackup, attrs);
    }

    /**
     * This version of commit also returns the post-read entry that
     * the change resulted in.
     */
    public LDAPEntry commitReturn(IConfigStore config, boolean createBackup, String[] attrs)
            throws EBaseException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        try {
            config.store(data);
        } catch (Exception e) {
            throw new EBaseException(e);
        }

        LDAPAttribute configAttr = new LDAPAttribute(attr, data.toByteArray());

        LDAPConstraints cons = new LDAPConstraints();
        cons.setServerControls(new LDAPPostReadControl(true, attrs));

        LDAPControl[] responseControls;

        // first attempt to modify; if modification fails (due
        // to no such object), try and add the entry instead.
        try {
            try {
                commitModify(configAttr, cons);
            } catch (LDAPException e) {
                if (e.getLDAPResultCode() == LDAPException.NO_SUCH_OBJECT) {
                    commitAdd(configAttr, cons);
                } else {
                    throw e;
                }
            }
            responseControls = conn.getResponseControls();

        } catch (LDAPException e) {
            throw new ELdapException("Unable to store " + dn + ": " + e, e);
        }

        LDAPPostReadControl control = (LDAPPostReadControl)
            LDAPUtil.getControl(LDAPPostReadControl.class, responseControls);

        return control.getEntry();
    }

    /**
     * Update the record via an LDAPModification.
     *
     * @param configAttr Config store attribute.
     * @return true on success, false if the entry does not exist.
     */
    private void commitModify(
            LDAPAttribute configAttr,
            LDAPConstraints cons)
            throws LDAPException {

        LDAPModification ldapMod = new LDAPModification(LDAPModification.REPLACE, configAttr);
        conn.modify(dn, ldapMod, cons);
    }

    /**
     * Add the LDAPEntry via LDAPConnection.add.
     *
     * @param configAttr Config store attribute.
     * @return true on success, false if the entry already exists.
     */
    private void commitAdd(
            LDAPAttribute configAttr,
            LDAPConstraints cons)
            throws LDAPException {

        LDAPAttributeSet attrSet = new LDAPAttributeSet(createAttrs);
        attrSet.add(configAttr);
        LDAPEntry ldapEntry = new LDAPEntry(dn, attrSet);
        conn.add(ldapEntry, cons);
    }
}
