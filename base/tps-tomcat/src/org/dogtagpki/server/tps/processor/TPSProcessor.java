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
// (C) 2013 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package org.dogtagpki.server.tps.processor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.dogtagpki.server.tps.TPSSession;
import org.dogtagpki.server.tps.TPSSubsystem;
import org.dogtagpki.server.tps.authentication.AuthUIParameter;
import org.dogtagpki.server.tps.authentication.TPSAuthenticator;
import org.dogtagpki.server.tps.channel.SecureChannel;
import org.dogtagpki.server.tps.cms.TKSComputeRandomDataResponse;
import org.dogtagpki.server.tps.cms.TKSComputeSessionKeyResponse;
import org.dogtagpki.server.tps.cms.TKSRemoteRequestHandler;
import org.dogtagpki.server.tps.engine.TPSEngine;
import org.dogtagpki.server.tps.profile.BaseTokenProfileResolver;
import org.dogtagpki.server.tps.profile.TokenProfileParams;
import org.dogtagpki.tps.apdu.APDU;
import org.dogtagpki.tps.apdu.APDUResponse;
import org.dogtagpki.tps.apdu.ExternalAuthenticateAPDU.SecurityLevel;
import org.dogtagpki.tps.apdu.GetDataAPDU;
import org.dogtagpki.tps.apdu.GetStatusAPDU;
import org.dogtagpki.tps.apdu.GetVersionAPDU;
import org.dogtagpki.tps.apdu.InitializeUpdateAPDU;
import org.dogtagpki.tps.apdu.SelectAPDU;
import org.dogtagpki.tps.main.TPSBuffer;
import org.dogtagpki.tps.main.TPSException;
import org.dogtagpki.tps.msg.BeginOp;
import org.dogtagpki.tps.msg.EndOp.TPSStatus;
import org.dogtagpki.tps.msg.ExtendedLoginRequest;
import org.dogtagpki.tps.msg.ExtendedLoginResponse;
import org.dogtagpki.tps.msg.LoginRequest;
import org.dogtagpki.tps.msg.LoginResponse;
import org.dogtagpki.tps.msg.StatusUpdateRequest;
import org.dogtagpki.tps.msg.TPSMessage;
import org.dogtagpki.tps.msg.TokenPDURequest;
import org.dogtagpki.tps.msg.TokenPDUResponse;
import org.mozilla.jss.CryptoManager.NotInitializedException;
import org.mozilla.jss.pkcs11.PK11SymKey;

import com.netscape.certsrv.apps.CMS;
import com.netscape.certsrv.authentication.IAuthCredentials;
import com.netscape.certsrv.authentication.IAuthManager;
import com.netscape.certsrv.authentication.IAuthToken;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.EPropertyNotFound;
import com.netscape.certsrv.base.IConfigStore;
import com.netscape.symkey.SessionKey;

public class TPSProcessor {

    public static final int RESULT_NO_ERROR = 0;
    public static final int RESULT_ERROR = -1;

    public static final int CPLC_DATA_SIZE = 47;
    public static final int CPLC_MSN_INDEX = 41;
    public static final int CPLC_MSN_SIZE = 4;

    public static final int INIT_UPDATE_DATA_SIZE = 28;
    public static final int DIVERSIFICATION_DATA_SIZE = 10;
    public static final int CARD_CRYPTOGRAM_OFFSET = 20;
    public static final int CARD_CRYPTOGRAM_SIZE = 8;
    public static final int CARD_CHALLENGE_OFFSET = 12;
    public static final int CARD_CHALLENGE_SIZE = 8;

    private boolean isExternalReg;

    private TPSSession session;
    private String selectedTokenType;

    private String currentTokenOperation;
    private String userid;

    private BeginOp beginMsg;

    public TPSProcessor(TPSSession session) {
        setSession(session);
    }

    protected void setCurrentTokenOperation(String op) {
        currentTokenOperation = op;
    }

    protected void setSession(TPSSession session) {
        if (session == null) {
            throw new NullPointerException("TPS session is null");
        }
        this.session = session;
    }

    protected TPSSession getSession() {
        return session;
    }

    protected void setBeginMessage(BeginOp msg) {
        beginMsg = msg;
    }

    public BeginOp getBeginMessage() {
        return beginMsg;
    }

    protected void setSelectedTokenType(String theTokenType) {

        if (theTokenType == null) {
            throw new NullPointerException("TPSProcessor.setSelectedTokenType: Attempt to set invalid null token type!");
        }
        selectedTokenType = theTokenType;
    }

    protected String getSelectedTokenType() {
        return selectedTokenType;
    }

    protected TPSBuffer extractTokenMSN(TPSBuffer cplc_data) throws TPSException {
        //Just make sure no one is inputing bogus cplc_data
        if (cplc_data == null || cplc_data.size() < CPLC_DATA_SIZE) {
            throw new TPSException("TPSProcessor.extractTokenMSN: Can't extract token msn from cplc data!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        TPSBuffer token_msn = cplc_data.substr(CPLC_MSN_INDEX, CPLC_MSN_SIZE);
        return token_msn;

    }

    protected TPSBuffer extractTokenCUID(TPSBuffer cplc_data) throws TPSException {
        //Just make sure no one is inputing bogus cplc_data
        if (cplc_data == null || cplc_data.size() < CPLC_DATA_SIZE) {
            CMS.debug("TPS_Processor.extractTokenCUID: cplc_data: invalid length.");
            throw new TPSException("TPSProcessor.extractTokenCUID: Can't extract cuid from cplc data!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        TPSBuffer token1 = cplc_data.substr(3, 4);
        TPSBuffer token2 = cplc_data.substr(19, 2);
        TPSBuffer token3 = cplc_data.substr(15, 4);

        TPSBuffer token_cuid = new TPSBuffer();

        token_cuid.add(token1);
        token_cuid.add(token2);
        token_cuid.add(token3);

        return token_cuid;

    }

    /**
     * Select applet.
     *
     * Global Platform Open Platform Card Specification
     * Version 2.0.1 Page 9-22
     *
     * Sample Data:
     *
     * _____________ CLA
     * | __________ INS
     * | | _______ P1
     * | | | ____ P2
     * | | | | _ Len
     * | | | | |
     * 00 A4 04 00 07
     * 53 4C 42 47 49 4E 41
     *
     * @throws IOException
     * @throws TPSException
     *
     */

    protected APDUResponse selectApplet(byte p1, byte p2, TPSBuffer aid) throws IOException, TPSException {

        CMS.debug("In TPS_Processor.SelectApplet.");

        if (aid == null || aid.size() == 0) {
            throw new TPSException("TPSProcessor.selectApplet: Invalid aid value!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        SelectAPDU select_apdu = new SelectAPDU(p1, p2, aid);

        //return the Response because the caller can
        //decide what to do, not every failure is fatal.
        //For instance the coolkey applet may not yet exist.
        return handleAPDURequest(select_apdu);

    }

    protected TPSBuffer getStatus() throws IOException, TPSException {

        CMS.debug("In TPS_Processor.GetStatus.");

        GetStatusAPDU get_status_apdu = new GetStatusAPDU();

        return handleAPDURequest(get_status_apdu).getData();
    }

    public APDUResponse handleAPDURequest(APDU apdu) throws IOException, TPSException {

        if (apdu == null) {
            throw new TPSException("TPSProcessor.handleAPDURequest: invalid incoming apdu!");
        }

        TokenPDURequest request_msg = new TokenPDURequest(apdu);

        try {
            session.write(request_msg);
        } catch (IOException e) {
            CMS.debug("TPS_Processor.HandleAPDURequest failed WriteMsg: " + e.toString());
            throw e;

        }

        TokenPDUResponse response_msg = null;

        try {
            response_msg = (TokenPDUResponse) session.read();
        } catch (IOException e) {
            CMS.debug("TPS_Processor.HandleAPDURequest failed ReadMsg: " + e.toString());
            throw e;

        }

        return response_msg.getResponseAPDU();
    }

    protected TPSBuffer getCplcData() throws IOException, TPSException {
        CMS.debug("In TPS_Processor.GetData");

        GetDataAPDU get_data_apdu = new GetDataAPDU();

        APDUResponse respApdu = handleAPDURequest(get_data_apdu);

        if (!respApdu.checkResult()) {
            throw new TPSException("TPSProcessor.getCplcData: Can't get data!", TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }
        TPSBuffer cplcData = respApdu.getData();

        if (cplcData.size() != CPLC_DATA_SIZE) {
            throw new TPSException("TPSProcessor.cplcData: Data invalid size!", TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return respApdu.getData();
    }

    protected TPSBuffer getAppletVersion() throws IOException, TPSException {
        //We return null if no applet present
        // This is not an error, the token can be blank.

        CMS.debug("In TPSProcessor.getAppletVersion");

        GetVersionAPDU get_version_apdu = new GetVersionAPDU();

        APDUResponse respApdu = handleAPDURequest(get_version_apdu);

        if (!respApdu.checkResult()) {
            CMS.debug("TPSProcessor.getAppletVersion: No applet version found on card!");
            return null;
        }

        TPSBuffer apdu_data = respApdu.getData();

        if (apdu_data.size() != 6) {
            CMS.debug("TPSProcessor.getAppletVersion: incorrect return data size!");
            throw new TPSException("TPSProcessor.getAppletVersion: invalid applet version string returned!");
        }

        TPSBuffer build_id = apdu_data.substr(0, 4);

        CMS.debug("TPSProcessor.getAppletVersion: returning: " + build_id.toHexString());

        return build_id;

    }

    TPSBuffer computeRandomData(int dataSize, String connId) throws TPSException {

        TKSRemoteRequestHandler tks = null;

        TKSComputeRandomDataResponse data = null;

        try {
            tks = new TKSRemoteRequestHandler(connId);
            data = tks.computeRandomData(dataSize);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.computeRandomData: Erorr getting random data from TKS!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        int status = data.getStatus();

        if (status != 0) {
            throw new TPSException("TPSProcessor.computeRandomData: Erorr getting random data from TKS!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return data.getRandomData();
    }

    protected TPSBuffer initializeUpdate(byte keyVersion, byte keyIndex, TPSBuffer randomData) throws IOException,
            TPSException {

        CMS.debug("In TPS_Processor.initializeUpdate.");
        InitializeUpdateAPDU initUpdate = new InitializeUpdateAPDU(keyVersion, keyIndex, randomData);

        APDUResponse resp = handleAPDURequest(initUpdate);

        if (!resp.checkResult()) {
            CMS.debug("TPSProcessor.initializeUpdate: Failed intializeUpdate!");
            throw new TPSException("TPSBuffer.initializeUpdate: Failed initializeUpdate!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);

        }

        TPSBuffer data = resp.getResultDataNoCode();

        if (data.size() != INIT_UPDATE_DATA_SIZE) {
            throw new TPSException("TPSBuffer.initializeUpdate: Invalid response from token!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return data;

    }

    protected SecureChannel setupSecureChannel(byte keyVersion, byte keyIndex, SecurityLevel securityLevel,
            String connId)
            throws IOException, TPSException {

        //Assume generating host challenge on TKS, we no longer support not involving the TKS.

        TPSBuffer randomData = computeRandomData(8, connId);
        CMS.debug("TPSProcessor.setupSecureChannel: obtained randomData: " + randomData.toHexString());

        TPSBuffer initUpdateResp = initializeUpdate(keyVersion, keyIndex, randomData);

        TPSBuffer key_diversification_data = initUpdateResp.substr(0, DIVERSIFICATION_DATA_SIZE);
        CMS.debug("TPSProcessor.setupSecureChannel: diversification data: " + key_diversification_data.toHexString());

        TPSBuffer key_info_data = initUpdateResp.substr(DIVERSIFICATION_DATA_SIZE, 2);
        CMS.debug("TPSProcessor.setupSecureChannel: key info data: " + key_info_data.toHexString());

        TPSBuffer card_cryptogram = initUpdateResp.substr(CARD_CRYPTOGRAM_OFFSET, CARD_CRYPTOGRAM_SIZE);
        CMS.debug("TPSProcessor.setupSecureChannel: card cryptogram: " + card_cryptogram.toHexString());

        TPSBuffer card_challenge = initUpdateResp.substr(CARD_CHALLENGE_OFFSET, CARD_CHALLENGE_SIZE);
        CMS.debug("TPSProcessor.setupSecureChannel: card challenge: " + card_challenge.toHexString());

        SecureChannel channel = null;

        try {
            channel = generateSecureChannel(connId, key_diversification_data, key_info_data, card_challenge,
                    card_cryptogram,
                    randomData);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.setupSecureChannel: Can't set up secure channel: " + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return channel;

    }

    protected SecureChannel generateSecureChannel(String connId, TPSBuffer keyDiversificationData,
            TPSBuffer keyInfoData, TPSBuffer cardChallenge, TPSBuffer cardCryptogram, TPSBuffer hostChallenge)
            throws EBaseException, TPSException {

        CMS.debug("TPSProcessor.generateSecureChannel: entering..");

        TPSEngine engine = getTPSEngine();

        SecureChannel channel = null;
        TPSBuffer hostCryptogram = null;

        TKSComputeSessionKeyResponse resp = engine.computeSessionKey(keyDiversificationData, keyInfoData,
                cardChallenge, hostChallenge, cardCryptogram,
                connId);

        hostCryptogram = resp.getHostCryptogram();

        if (hostCryptogram == null) {
            new TPSException("TPSProcessor.generateSecureChannel: No host cryptogram returned from token!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);

        }

        PK11SymKey sharedSecret = null;

        try {
            sharedSecret = getSharedSecretTransportKey(connId);
        } catch (Exception e) {
            CMS.debug(e);
            throw new TPSException("TPSProcessor.generateSecureChannel: Can't get shared secret key!: " + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        PK11SymKey sessionKey = null;
        PK11SymKey encSessionKey = null;
        String tokenName = "Internal Key Storage Token";

        try {
            TPSBuffer sessionKeyWrapped = resp.getSessionKey();
            TPSBuffer encSessionKeyWrapped = resp.getEncSessionKey();

            sessionKey = SessionKey.UnwrapSessionKeyWithSharedSecret(tokenName, sharedSecret,
                    sessionKeyWrapped.toBytesArray());

            if (sessionKey == null) {
                CMS.debug("TPSProcessor.generateSecureChannel: Can't extract session key!");
                throw new TPSException("TPSProcessor.generateSecureChannel: Can't extract session key!",
                        TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
            }
            CMS.debug("TPSProcessor.generateSecureChannel: retrieved session key: " + sessionKey);

            encSessionKey = SessionKey.UnwrapSessionKeyWithSharedSecret(tokenName, sharedSecret,
                    encSessionKeyWrapped.toBytesArray());

            if (encSessionKey == null) {
                CMS.debug("TPSProcessor.generateSecureChannel: Can't extract enc session key!");
                throw new TPSException("TPSProcessor.generateSecureChannel: Can't extract enc session key!",
                        TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
            }

            CMS.debug("TPSProcessor.generateSecureChannel: retrieved enc session key: " + encSessionKey);
        } catch (Exception e) {
            CMS.debug(e);
            e.printStackTrace();
            throw new TPSException("TPSProcessor.generateSecureChannel: Problem extracting session keys! " + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        TPSBuffer drmDesKey = null;
        TPSBuffer kekDesKey = null;
        TPSBuffer keyCheck = null;

        if (checkServerSideKeyGen(connId)) {
            //ToDo handle server side keygen.

        }

        channel = new SecureChannel(this, sessionKey, encSessionKey, drmDesKey,
                kekDesKey, keyCheck, keyDiversificationData, cardChallenge,
                cardCryptogram, hostChallenge, hostCryptogram);

        return channel;
    }

    protected void upgradeApplet(String operation, String new_version, SecurityLevel securityLevel,
            Map<String, String> extensions, String connId, int startProgress, int endProgress) throws IOException,
            TPSException {

        String NetKeyAID = null;
        String NetKeyPAID = null;
        String CardMgrAID = null;

        IConfigStore configStore = CMS.getConfigStore();

        try {
            //These defaults are well known, it is safe to use them.

            NetKeyAID = configStore.getString(TPSEngine.CFG_APPLET_NETKEY_INSTANCE_AID,
                    TPSEngine.CFG_DEF_NETKEY_INSTANCE_AID);
            CMS.debug("In TPS_Processor.upgradeApplet. CardManagerAID: " + " NetKeyAID: " + NetKeyAID);
            NetKeyPAID = configStore.getString(TPSEngine.CFG_APPLET_NETKEY_FILE_AID, TPSEngine.CFG_DEF_NETKEY_FILE_AID);
            CardMgrAID = configStore.getString(TPSEngine.CFG_APPLET_CARDMGR_INSTANCE_AID,
                    TPSEngine.CFG_DEF_CARDMGR_INSTANCE_AID);

        } catch (EBaseException e1) {
            CMS.debug("TPS_Processor.upgradeApplet: Internal Error obtaining mandatory config values. Error: " + e1);
            throw new TPSException("TPS error getting config values from config store.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        TPSBuffer netkeyAIDBuff = new TPSBuffer(NetKeyAID);
        TPSBuffer netkeyPAIDBuff = new TPSBuffer(NetKeyPAID);
        TPSBuffer cardMgrAIDBuff = new TPSBuffer(CardMgrAID);

        int channelBlockSize = getChannelBlockSize();
        int channelInstanceSize = getChannelInstanceSize();
        int channelAppletMemSize = getAppletMemorySize();
        int defKeyVersion = getChannelDefKeyVersion();
        int defKeyIndex = getChannelDefKeyIndex();
        byte[] appletData = null;

        String directory = getAppletDirectory(operation);

        CMS.debug("TPSProcessor.upgradeApplet: applet target directory: " + directory);

        String appletFileExt = getAppletExtension();

        String appletFilePath = directory + "/" + new_version + "." + appletFileExt;

        CMS.debug("TPSProcessor.upgradeApplet: targe applet file name: " + appletFilePath);

        appletData = getAppletFileData(appletFilePath);

        APDUResponse select = selectApplet((byte) 0x04, (byte) 0x00, cardMgrAIDBuff);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.format: Can't selelect the card manager!");
        }

        SecureChannel channel = setupSecureChannel((byte) defKeyVersion, (byte) defKeyIndex, securityLevel, connId);

        channel.externalAuthenticate();
        channel.deleteFileX(netkeyAIDBuff);
        channel.deleteFileX(netkeyPAIDBuff);

        // Next step will be to load the applet file to token.

        TPSBuffer empty = new TPSBuffer();

        channel.installLoad(netkeyPAIDBuff, empty, appletData.length);

        TPSBuffer appletDataBuff = new TPSBuffer(appletData);

        channel.loadFile(appletDataBuff, channelBlockSize, startProgress, endProgress);

        channel.installApplet(netkeyPAIDBuff, netkeyAIDBuff, (byte) 0, channelInstanceSize, channelAppletMemSize);

        //Now select our new applet

        select = selectApplet((byte) 0x04, (byte) 0x00, netkeyAIDBuff);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.upgradeApplet: Cannot select newly created applet!",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

    }

    protected byte[] getAppletFileData(String appletFilePath) throws IOException, TPSException {

        if (appletFilePath == null) {
            throw new TPSException("TPSProcessor.getAppletFileData: Invalid applet file name.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        byte[] contents = null;
        try {
            Path path = Paths.get(appletFilePath);
            contents = Files.readAllBytes(path);

        } catch (IOException e) {
            CMS.debug("TPSProcessor.getAppletFileData: IOException " + e);
            throw e;
        } catch (Exception e) {
            CMS.debug("PSProcessor.getAppletFileData: Exception: " + e);
            throw new TPSException("TPSProcessor.getAppletFileData: Exception: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("TPSProcessor.getAppletFileData: data: " + contents);

        return contents;
    }

    /**
     * getAuthentication gets Authentication per configuration
     *
     * @param prefix config prefix for tokenType
     * @param tokenType the tokenType(profile)
     * @return Authentication
     */
    public TPSAuthenticator getAuthentication(String prefix, String tokenType)
            throws EBaseException {
        CMS.debug("TPSProcessor.getAuthentication");
        if (prefix.isEmpty() || tokenType.isEmpty()) {
            CMS.debug("TPSProcessor.getAuthentication: missing parameters: prefix or tokenType");
            throw new EBaseException("TPSProcessor.getAuthentication: missing parameters: prefix or tokenType");
        }
        IConfigStore configStore = CMS.getConfigStore();
        String configName = prefix + "." + tokenType + ".auth.id";
        String authId;

        CMS.debug("TPSProcessor.getAuthentication: getting config: " +
                configName);
        authId = configStore.getString(configName);

        TPSSubsystem subsystem =
                (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);
        TPSAuthenticator authInst =
                subsystem.getAuthenticationManager().getAuthInstance(authId);
        return authInst;
    }

    /**
     * authenticateUser authenticates a user using specified authentication
     *
     * @param op "enrollment", "format", or "pinReset" //TODO: for tokendb activity log
     * @param prefix "op.enroll", "op.format", or "op.pinReset"
     * @param tokenType the profile name
     * @param userCred IAuthCredentials obtained from a successful requestUserId call
     * @return IAuthToken information relating to the performed authentication
     *                    -- plugin-specific
     */
    public IAuthToken authenticateUser(
            String op,
            TPSAuthenticator userAuth,
            IAuthCredentials userCred)
            throws EBaseException, TPSException {
        /**
         * TODO: isExternalReg is not handled until decision made
         */
        CMS.debug("TPSProcessor.authenticateUser");
        if (op.isEmpty() || userAuth == null || userCred == null) {
            CMS.debug("TPSProcessor.authenticateUser: missing parameter(s): op, userAuth, or userCred");
            throw new EBaseException("TPSProcessor.getAuthentication: missing parameter(s): op, userAuth, or userCred");
        }
        IAuthManager auth = userAuth.getAuthManager();

        try {
            // Authenticate user
            IAuthToken aToken = auth.authenticate(userCred);
            if (aToken != null) {
                CMS.debug("TPSProcessor.authenticateUser: authentication success");
                return aToken;
            } else {
                CMS.debug("TPSProcessor.authenticateUser: authentication failure with aToken null");
                throw new TPSException("TPS error user authentication failed.",
                        TPSStatus.STATUS_ERROR_LOGIN);
            }
        } catch (EBaseException e) {
            CMS.debug("TPSProcessor.authenticateUser: authentication failure:" + e);
            throw new TPSException("TPS error user authentication failed.",
                    TPSStatus.STATUS_ERROR_LOGIN);
        }
    }

    /**
     * requestUserId sends message to client to request for user credential
     * per authentication plugin
     *
     * @param op "enrollment", "format", or "pinReset" //TODO: for tokendb activity log
     * @param cuid token CUID //TODO: for tokendb activity log
     * @param extensions message extensions
     * @return IAuthCredentials containing user credential needed for authentication
     */
    IAuthCredentials requestUserId(String op, String cuid, TPSAuthenticator auth, Map<String, String> extensions)
            throws IOException, TPSException, EBaseException {
        CMS.debug("TPSProcessor.requestUserId");
        if (op.isEmpty() ||
                cuid.isEmpty() || auth == null) {
            CMS.debug("TPSProcessor.requestUserId: missing parameter(s): op, cuid, or auth");
            throw new EBaseException("TPSProcessor.requestUserId: missing parameter(s): op, cuid, or auth");
        }

        IAuthCredentials login;
        if (extensions != null &&
                extensions.get("extendedLoginRequest") != null) {
            // default locale will be "en"
            String locale = extensions.get("locale");
            if (extensions.get("locale") == null) {
                locale = "en";
            }
            // title
            String title = auth.getUiTitle(locale);
            if (title.isEmpty())
                title = auth.getUiTitle("en");
	    // description
            String description = auth.getUiDescription(locale);
            if (description.isEmpty())
                description = auth.getUiTitle("en");
            // parameters
            HashMap<String, AuthUIParameter> authParamSet = auth.getUiParamSet();
            Set<String> params = new HashSet<String>();
            for (Map.Entry<String, AuthUIParameter> entry: authParamSet.entrySet()) {
                params.add(auth.getUiParam(entry.getKey()).toString(locale));
                CMS.debug("TPSProcessor.requestUserId: for extendedLoginRequest, added param: " +
                        auth.getUiParam(entry.getKey()).toString(locale));
            }

            login = requestExtendedLogin(0 /* invalid_pw */, 0 /* blocked */,
                    params, title, description, auth);
        } else {
            login = requestLogin(0 /* invalid_pw */, 0 /* blocked */, auth);
        }

        return login;
    }

    /**
     * mapCredFromMsgResponse fills up authManager required auth credentials
     * with mapped values from client
     * configuration example:
     *
     * auths.instance.ldap1.ui.id.UID.credMap.msgCred=screen_name
     * auths.instance.ldap1.ui.id.UID.credMap.authCred=uid
     *
     * auths.instance.ldap1.ui.id.PASSWORD.credMap.msgCred=password
     * auths.instance.ldap1.ui.id.PASSWORD.credMap.authCred=pwd
     *
     * @param response the message response to be mapped
     * @param auth the authentication for mapping consultation
     * @return IAuthCredentials auth credential for auth manager
     */
    public IAuthCredentials mapCredFromMsgResponse(TPSMessage response, TPSAuthenticator auth)
            throws EBaseException {
        CMS.debug("TPSProcessor.mapCredFromMsgResponse");
        if (response == null || auth == null) {
            CMS.debug("TPSProcessor.mapCredFromMsgResponse: missing parameter(s): response or auth");
            throw new EBaseException("TPSProcessor.mapCredFromMsgResponse: missing parameter(s): response or auth");
        }
        IAuthCredentials login =
                new com.netscape.certsrv.authentication.AuthCredentials();

        String[] requiredCreds = auth.getAuthManager().getRequiredCreds();
        for (String cred : requiredCreds) {
            String name = auth.getCredMap(cred);
            login.set(cred, response.get(name));
        }

        return login;
    }

    /**
     * Requests login ID and password from user.
     */
    public IAuthCredentials requestExtendedLogin(int invalidPW, int blocked,
            Set<String> parameters,
            String title,
            String description,
            TPSAuthenticator auth)
            throws IOException, TPSException, EBaseException {

        CMS.debug("TPSProcessor.requestExtendedLogin");
        if (parameters == null || title.isEmpty() ||
                description.isEmpty() || auth == null) {
            CMS.debug("TPSProcessor.requestExtendedLogin: missing parameter(s): parameters, title, description, or auth");
            throw new EBaseException(
                    "TPSProcessor.requestExtendedLogin: missing parameter(s): parameters, title, description, or auth");
        }
        ExtendedLoginRequest loginReq =
                new ExtendedLoginRequest(invalidPW, blocked, parameters, title, description);

        try {
            session.write(loginReq);
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestExtendedLogin failed WriteMsg: " + e.toString());
            throw e;
        }
        CMS.debug("TPSProcessor.requestExtendedLogin: extendedLoginRequest sent");

        ExtendedLoginResponse loginResp = null;
        try {
            loginResp = (ExtendedLoginResponse) session.read();
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestExtendedLogin failed ReadMsg: " + e.toString());
            throw e;
        }

        IAuthCredentials login = mapCredFromMsgResponse(loginResp, auth);

        return login;
    }

    /**
     * Requests login ID and password from user.
     */
    public IAuthCredentials requestLogin(int invalidPW, int blocked,
            TPSAuthenticator auth)
            throws IOException, TPSException, EBaseException {

        CMS.debug("TPSProcessor.requestLogin");
        if (auth == null) {
            CMS.debug("TPSProcessor.requestLogin: missing parameter(s): parameters, title, description, or auth");
            throw new EBaseException(
                    "TPSProcessor.requestLogin: missing parameter(s): parameters, title, description, or auth");
        }
        LoginRequest loginReq = new LoginRequest(invalidPW, blocked);

        try {
            session.write(loginReq);
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestLogin failed WriteMsg: " + e.toString());
            throw e;
        }
        CMS.debug("TPSProcessor.requestLogin: loginRequest sent");

        LoginResponse loginResp = null;
        try {
            loginResp = (LoginResponse) session.read();
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestLogin failed ReadMsg: " + e.toString());
            throw e;
        }

        IAuthCredentials login = mapCredFromMsgResponse(loginResp, auth);
        return login;
    }

    protected void format() throws TPSException, IOException {

        IConfigStore configStore = CMS.getConfigStore();

        String CardManagerAID = null;
        String NetKeyAID = null;
        String appletVersion = null;

        TPSSubsystem tps = (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);
        TPSEngine engine = tps.getEngine();

        String External_Reg_Cfg = TPSEngine.CFG_EXTERNAL_REG + "." + "enable";
        boolean isExternalReg = false;
        String resolverInstName = "";

        setCurrentTokenOperation("format");

        try {
            //These defaults are well known, it is safe to use them.
            CardManagerAID = configStore.getString(TPSEngine.CFG_APPLET_CARDMGR_INSTANCE_AID,
                    TPSEngine.CFG_DEF_CARDMGR_INSTANCE_AID);
            NetKeyAID = configStore.getString(TPSEngine.CFG_APPLET_NETKEY_INSTANCE_AID,
                    TPSEngine.CFG_DEF_NETKEY_INSTANCE_AID);
            CMS.debug("In TPS_Processor.Format. CardManagerAID: " + CardManagerAID + " NetKeyAID: " + NetKeyAID);
            this.isExternalReg = configStore.getBoolean(External_Reg_Cfg, false);
            CMS.debug("In TPSProcessor.format isExternalReg: " + isExternalReg);
            resolverInstName = configStore.getString(TPSEngine.OP_FORMAT_PREFIX+
                "."+ TPSEngine.CFG_PROFILE_RESOLVER, TPSEngine.CFG_DEF_FORMAT_PROFILE_RESOLVER);
        } catch (EBaseException e1) {
            CMS.debug("TPS_Processor.Format: Internal Error obtaining mandatory config values. Error: " + e1);
            throw new TPSException("TPS error getting config values from config store.",
                    TPSStatus.STATUS_ERROR_MISCONFIGURATION);
        }

        TPSBuffer aidBuf = new TPSBuffer(CardManagerAID);

        APDUResponse select = selectApplet((byte) 0x04, (byte) 0x00, aidBuf);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.format: Can't selelect the card manager!");
        }

        TPSBuffer cplc_data = getCplcData();
        CMS.debug("cplc_data: " + cplc_data.toString());

        TPSBuffer token_cuid = extractTokenCUID(cplc_data);
        String cuid = token_cuid.toHexString();

        TPSBuffer token_msn = extractTokenMSN(cplc_data);
        String msn = token_msn.toHexString();

        /**
         * Checks if the netkey has the required applet version.
         */

        TPSBuffer netkeyAid = new TPSBuffer(NetKeyAID);

        // We don't care if the above fails now. getStatus will determine outcome.

        select = selectApplet((byte) 0x04, (byte) 0x00, netkeyAid);

        CMS.debug("TPSProcessor.format: First time select netkey applet: " + select.checkResult());

        TPSBuffer token_status = getStatus();

        byte major_version = 0x0;
        byte minor_version = 0x0;
        byte app_major_version = 0x0;
        byte app_minor_version = 0x0;

        CMS.debug("TPS_Processor.format: status: " + token_status.toHexString());
        if (token_status.size() >= 4) {
            major_version = token_status.at(0);
            minor_version = token_status.at(1);
            app_major_version = token_status.at(2);
            app_minor_version = token_status.at(3);
        }
        CMS.debug("TPSProcessor.format: major_version " + major_version + " minor_version: " + minor_version
                + " app_major_version: " + app_major_version + " app_minor_version: " + app_minor_version);

        String tokenType = "tokenType";
        IAuthCredentials userCred=
                new com.netscape.certsrv.authentication.AuthCredentials();
        if (isExternalReg) {
            //ToDo, do some external Reg stuff along with authentication
            tokenType = "externalRegAddToToken";
        } else {
            CMS.debug("In TPSProcessor.format isExternalReg: OFF");
            /*
             * Note: op.format.tokenProfileResolver=none indicates no resolver
             *    plugin used (tokenType resolved perhaps via authentication)
             */
            if (!resolverInstName.equals("none") && (selectedTokenType == null)) {
                tokenType = resolveTokenProfile(resolverInstName, cuid, msn, major_version, minor_version);
                CMS.debug("TPSProcessor.format: calculated tokenType: " + tokenType);
            } else {
                CMS.debug("TPSProcessor.format: : selectedTokenType already set: " + selectedTokenType);
            }
        }

        // isExternalReg : user already authenticated earlier
        if (!isExternalReg) {
            // authenticate per profile/tokenType configuration
            String configName = TPSEngine.OP_FORMAT_PREFIX + "." + tokenType + ".auth.enable";
            boolean isAuthRequired;
            try {
                CMS.debug("TPSProcessor.format: getting config: " + configName);
                isAuthRequired = configStore.getBoolean(configName, true);
            } catch (EBaseException e) {
                CMS.debug("TPSProcessor.format: Internal Error obtaining mandatory config values. Error: " + e);
                throw new TPSException("TPS error getting config values from config store.",
                        TPSStatus.STATUS_ERROR_MISCONFIGURATION);
            }
            if (isAuthRequired) {
                try {
                    TPSAuthenticator userAuth =
                            getAuthentication(TPSEngine.OP_FORMAT_PREFIX, tokenType);
                    userCred = requestUserId("format", cuid, userAuth, beginMsg.getExtensions());
                    IAuthToken authToken = authenticateUser("format", userAuth, userCred);
                    userid = authToken.getInString("userid");
                    CMS.debug("TPSProcessor.format:: auth token userid="+ userid);
                } catch (Exception e) {
                    // all exceptions are considered login failure
                    CMS.debug("TPSProcessor.format:: authentication exception thrown: " + e);
                    throw new TPSException("TPS error user authentication failed.",
                            TPSStatus.STATUS_ERROR_LOGIN);
                }
            }
        }

        /**
         * TODO:
         * isExternalReg is not handled beyond this point until decided
         */

        //Now check provided profile

        checkProfileStateOK();

        if (engine.isTokenPresent(cuid)) {
            //ToDo

        } else {
            checkAllowUnknownToken(TPSEngine.OP_FORMAT_PREFIX);
        }

        TPSBuffer build_id = getAppletVersion();

        if (build_id == null) {
            checkAllowNoAppletToken(TPSEngine.OP_FORMAT_PREFIX);
        } else {
            appletVersion = Integer.toHexString(app_major_version) + "." + Integer.toHexString(app_minor_version) + "."
                    + build_id.toHexString();
        }

        String appletRequiredVersion = checkForAppletUpgrade(TPSEngine.OP_FORMAT_PREFIX);

        CMS.debug("TPSProcessor.format: appletVersion found: " + appletVersion + "requiredVersion: "
                + appletRequiredVersion);

        SecurityLevel secLevel = SecurityLevel.SECURE_MSG_MAC_ENC;

        String tksConnId = getTKSConnectorID();

        upgradeApplet(TPSEngine.OP_FORMAT_PREFIX, appletRequiredVersion, secLevel,
                beginMsg.getExtensions(), tksConnId,
                10, 90);

        CMS.debug("TPSProcessor.format: Completed applet upgrade.");

        // Add issuer info to the token

        if (checkIssuerInfoEnabled()) {

            int defKeyIndex = getChannelDefKeyIndex();
            int defKeyVersion = getChannelDefKeyVersion();

            SecureChannel channel = setupSecureChannel((byte) defKeyVersion, (byte) defKeyIndex, secLevel, tksConnId);

            channel.externalAuthenticate();

            String issuer = getIssuerInfoValue();

            // We know this better be ASCII value URL.
            byte[] issuer_bytes = issuer.getBytes("US-ASCII");
            TPSBuffer issuerInfoBuff = new TPSBuffer(issuer_bytes);

            channel.setIssuerInfo(issuerInfoBuff);

        }

        if (requiresStatusUpdate()) {
            statusUpdate(100, "PROGRESS_DONE");
        }

        //ToDo:  Symmetric Key Changeover

        // ToDo: Update Token DB

        // ToDo:  Revoke certificates

    }

    /**
     * @param resolverInstName
     * @param cuid
     * @param msn
     * @param major_version
     * @param minor_version
     * @return
     */
    protected String resolveTokenProfile(
            String resolverInstName,
            String cuid,
            String msn,
            byte major_version,
            byte minor_version)
            throws TPSException {
        String tokenType;
        try {
            TokenProfileParams pParams = new TokenProfileParams();
            CMS.debug("In TPSProcessor.resolveTokenProfile : after new TokenProfileParams");
            pParams.set(TokenProfileParams.PROFILE_PARAM_MAJOR_VERSION,
                    String.valueOf((int)major_version));
            pParams.set(TokenProfileParams.PROFILE_PARAM_MINOR_VERSION,
                    String.valueOf((int)minor_version));
            pParams.set(TokenProfileParams.PROFILE_PARAM_CUID, cuid);
            pParams.set(TokenProfileParams.PROFILE_PARAM_MSN, msn);
            if (beginMsg.getExtensions() != null) {
                pParams.set(TokenProfileParams.PROFILE_PARAM_EXT_TOKEN_TYPE,
                        beginMsg.getExtensions().get("tokenType"));
                pParams.set(TokenProfileParams.PROFILE_PARAM_EXT_TOKEN_ATR,
                        beginMsg.getExtensions().get("tokenATR"));
            }
            CMS.debug("In TPSProcessor.resolveTokenProfile : after setting TokenProfileParams");
            TPSSubsystem subsystem =
                (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);
            BaseTokenProfileResolver resolverInst =
                subsystem.getProfileResolverManager().getResolverInstance(resolverInstName);
            tokenType = resolverInst.getTokenType(pParams);
            CMS.debug("In TPSProcessor.resolveTokenProfile : profile resolver result: "+ tokenType);
            setSelectedTokenType(tokenType);
        } catch (EBaseException et) {
            CMS.debug("In TPSProcessor.resolveTokenProfile exception:"+et);
            throw new TPSException("TPSProcessor.resolveTokenProfile failed.",
                    TPSStatus.STATUS_ERROR_DEFAULT_TOKENTYPE_NOT_FOUND);
        }
        return tokenType;
    }

    protected String getIssuerInfoValue() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String info = null;

        String config = "op." + currentTokenOperation + "." + selectedTokenType + "." + TPSEngine.CFG_ISSUER_INFO_VALUE;

        CMS.debug("TPSProcessor.getIssuerInfoValue: config: " + config);
        try {
            info = configStore.getString(config, null);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getIssuerInfoValue: Internal error finding config value.");

        }

        if (info == null) {
            throw new TPSException("TPSProcessor.getIssuerInfoValue: Can't find issuer info value in the config.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("TPSProcessor.getIssuerInfoValue: returning: " + info);

        return info;
    }

    void checkProfileStateOK() throws TPSException {

        IConfigStore configStore = CMS.getConfigStore();

        String profileConfig = "config.Profiles." + selectedTokenType + ".state";
        String profileState = null;

        try {
            profileState = configStore.getString(profileConfig, TPSEngine.CFG_ENABLED);
        } catch (EBaseException e) {
            //Default TPSException will return a "contact admin" error code.
            throw new TPSException(
                    "TPSProcessor.checkProfileStateOK: internal error in getting profile state from config.");
        }

        if (!profileState.equals(TPSEngine.CFG_ENABLED)) {
            CMS.debug("TPSProcessor.checkProfileStateOK: profile specifically disabled.");
            throw new TPSException("TPSProcessor.checkProfileStateOK: profile disabled!");
        }

    }

    protected boolean checkIssuerInfoEnabled() throws TPSException {

        CMS.debug("TPSProcessor.checkIssuerEnabled entering...");

        IConfigStore configStore = CMS.getConfigStore();

        String issuerEnabledConfig = "op." + currentTokenOperation + "." + selectedTokenType + "."
                + TPSEngine.CFG_ISSUER_INFO_ENABLE;

        CMS.debug("TPSProcessor.checkIssuerEnabled config to check: " + issuerEnabledConfig);

        boolean issuerInfoEnabled = false;

        try {
            issuerInfoEnabled = configStore.getBoolean(issuerEnabledConfig, false);
        } catch (EBaseException e) {
            //Default TPSException will return a "contact admin" error code.
            throw new TPSException(
                    "TPSProcessor.checkIssuerInfo: internal error in getting value from config.");
        }

        CMS.debug("TPSProcessor.checkIssuerEnabled returning: " + issuerInfoEnabled);
        return issuerInfoEnabled;

    }

    boolean checkServerSideKeyGen(String connId) throws TPSException {

        boolean result;
        IConfigStore configStore = CMS.getConfigStore();

        String profileConfig = "conn." + connId + "." + ".serverKeygen";

        try {
            result = configStore.getBoolean(profileConfig, false);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor: checkServerSideKeyGen: Internal error obtaining config value!");
        }

        return result;
    }

    void checkAllowNoAppletToken(String operation) throws TPSException {
        boolean allow = true;
        IConfigStore configStore = CMS.getConfigStore();

        String noAppletConfig = operation + "." + selectedTokenType + "." + TPSEngine.CFG_ALLOW_NO_APPLET;

        try {
            allow = configStore.getBoolean(noAppletConfig, true);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.checkAllowNoAppletToken: Internal error getting config param.");
        }

        if (!allow) {
            throw new TPSException("TPSProcessor.checkAllowNoAppletToken: token without applet not permitted!",
                    TPSStatus.STATUS_ERROR_CONTACT_ADMIN);
        }

    }

    protected String checkForAppletUpgrade(String operation) throws TPSException {
        String requiredVersion = null;
        IConfigStore configStore = CMS.getConfigStore();

        String appletRequiredConfig = operation + "." + selectedTokenType + "."
                + TPSEngine.CFG_APPLET_UPDATE_REQUIRED_VERSION;

        try {
            requiredVersion = configStore.getString(appletRequiredConfig, null);
        } catch (EBaseException e) {
            throw new TPSException(
                    "TPSProcessor.checkForAppletUpgrade: Can't find applet required Version. Internal error obtaining version.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        if (requiredVersion == null) {
            throw new TPSException("TPSProcessor.checkForAppletUpgrade: Can't find applet required Version.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("TPSProcessor.checkForAppletUpgrade: returning: " + requiredVersion);

        return requiredVersion;
    }

    protected void checkAllowUnknownToken(String operation) throws TPSException {
        boolean allow = true;

        IConfigStore configStore = CMS.getConfigStore();

        String unknownConfig = operation + "." + TPSEngine.CFG_ALLOW_UNKNOWN_TOKEN;

        try {
            allow = configStore.getBoolean(unknownConfig, true);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.checkAllowUnknownToken: Internal error getting config value.");
        }

        if (allow == false) {
            throw new TPSException(
                    "TPSProcessor.checkAllowUnknownToken: Unknown tokens not allowed for this operation!",
                    TPSStatus.STATUS_ERROR_TOKEN_DISABLED);
        }

    }

    protected String getTKSConnectorID() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String id = null;

        String config = "op." + currentTokenOperation + "." + selectedTokenType + ".tks.conn";

        try {
            id = configStore.getString(config, "tks1");
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getTKSConnectorID: Internal error finding config value.");

        }

        CMS.debug("TPSProcessor.getTKSConectorID: returning: " + id);

        return id;
    }

    protected String getAppletExtension() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String extension = null;
        String extensionConfig = TPSEngine.CFG_APPLET_EXTENSION;

        try {
            extension = configStore.getString(extensionConfig, "ijc");
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getAppletExtension: Internal error finding config value.");

        }

        CMS.debug("TPSProcessor.getAppletExtension: returning: " + extension);

        return extension;
    }

    protected String getAppletDirectory(String operation) throws TPSException {

        IConfigStore configStore = CMS.getConfigStore();
        String directory = null;

        String directoryConfig = operation + "." + selectedTokenType + "." + TPSEngine.CFG_APPLET_DIRECTORY;

        //We need a directory
        try {
            directory = configStore.getString(directoryConfig);
        } catch (EPropertyNotFound e) {
            throw new TPSException("TPSProcessor.getAppletDirectory: Required config param missing.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getAppletDirectory: Internal error finding config value.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("getAppletDirectory: returning: " + directory);
        return directory;
    }

    protected int getChannelBlockSize() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int blockSize = 0;
        try {
            blockSize = configStore.getInteger(TPSEngine.CFG_CHANNEL_BLOCK_SIZE, TPSEngine.CFG_CHANNEL_DEF_BLOCK_SIZE);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelBlockSize: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcess.getChannelBlockSize: returning: " + blockSize);
        return blockSize;

    }

    protected int getChannelInstanceSize() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int instanceSize = 0;
        try {
            instanceSize = configStore.getInteger(TPSEngine.CFG_CHANNEL_INSTANCE_SIZE,
                    TPSEngine.CFG_CHANNEL_DEF_INSTANCE_SIZE);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelInstanceSize: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcess.getChannelInstanceSize: returning: " + instanceSize);

        return instanceSize;

    }

    protected int getAppletMemorySize() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int memSize = 0;
        try {
            memSize = configStore.getInteger(TPSEngine.CFG_CHANNEL_APPLET_MEMORY_SIZE,
                    TPSEngine.CFG_CHANNEL_DEF_APPLET_MEMORY_SIZE);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getAppletMemorySize: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }
        CMS.debug("TPSProcess.getAppletMemorySize: returning: " + memSize);

        return memSize;
    }

    protected int getChannelDefKeyVersion() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int ver = 0;
        try {
            ver = configStore.getInteger(TPSEngine.CFG_CHANNEL_DEFKEY_VERSION, 0x0);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelDefKeyVersion: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcessor.getChannelDefKeyVersion: " + ver);

        return ver;

    }

    protected int getChannelDefKeyIndex() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int index = 0;
        try {
            index = configStore.getInteger(TPSEngine.CFG_CHANNEL_DEFKEY_INDEX, 0x0);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelDefKeyVersion: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcessor.getChannelDefKeyIndex: " + index);

        return index;

    }

    protected PK11SymKey getSharedSecretTransportKey(String connId) throws TPSException, NotInitializedException {

        IConfigStore configStore = CMS.getConfigStore();
        String sharedSecretName = null;
        try {
            String configName = "conn." + connId + ".tksSharedSymKeyName";
            sharedSecretName = configStore.getString(configName, "sharedSecret");

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getSharedSecretTransportKey: Internal error finding config value: "
                    + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);

        }

        CMS.debug("TPSProcessor.getSharedSecretTransportKey: calculated key name: " + sharedSecretName);

        String symmKeys = null;
        boolean keyPresent = false;
        try {
            symmKeys = SessionKey.ListSymmetricKeys("internal");
            CMS.debug("TPSProcessor.getSharedSecretTransportKey: symmKeys List: " + symmKeys);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            CMS.debug(e);
        }

        for (String keyName : symmKeys.split(",")) {
            if (sharedSecretName.equals(keyName)) {
                CMS.debug("TPSProcessor.getSharedSecret: shared secret key found!");
                keyPresent = true;
                break;
            }

        }

        if (!keyPresent) {
            throw new TPSException("TPSProcessor.getSharedSecret: Can't find shared secret!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        // We know for now that shared secret is on this token
        String tokenName = "Internal Key Storage Token";
        PK11SymKey sharedSecret = SessionKey.GetSymKeyByName(tokenName, sharedSecretName);

        CMS.debug("TPSProcessor.getSharedSecret: SymKey returns: " + sharedSecret);

        return sharedSecret;

    }

    public boolean getIsExternalReg() {
        return isExternalReg;
    }

    public void process(BeginOp beginMsg) throws TPSException, IOException {

        if (beginMsg == null) {
            throw new TPSException("TPSProcessor.process: invalid input data, not beginMsg provided.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }
        setBeginMessage(beginMsg);

        format();
    }

    public void statusUpdate(int status, String info) throws IOException {

        CMS.debug("In statusUpdate status: " + " info: " + info);

        StatusUpdateRequest statusUpdate = new StatusUpdateRequest(status, info);
        session.write(statusUpdate);

        //We don't really care about the response, just that we get it.

        session.read();

    }


    public TPSEngine getTPSEngine() {
        TPSSubsystem subsystem =
                (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);

        return subsystem.getEngine();

    }

    // Do the incoming extensions support status update?
    public boolean requiresStatusUpdate() {

        boolean result = false;

        // We can't get here without a begin message established.
        String update = getBeginMessage().getExtension(BeginOp.STATUS_UPDATE_EXTENSION_NAME);

        if (update != null && update.equals("true")) {
            result = true;
        }

        return result;

    }

    public static void main(String[] args) {
    }

}
