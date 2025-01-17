/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.net.security.jwt;

import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import io.cryostat.core.log.Logger;
import io.cryostat.net.web.WebServer;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import dagger.Lazy;

public class AssetJwtHelper {

    public static final String RESOURCE_CLAIM = "resource";
    public static final String JMXAUTH_CLAIM = "jmxauth";

    private final Lazy<WebServer> webServer;
    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final JWEEncrypter encrypter;
    private final JWEDecrypter decrypter;
    private final boolean subjectRequired;

    AssetJwtHelper(
            Lazy<WebServer> webServer,
            JWSSigner signer,
            JWSVerifier verifier,
            JWEEncrypter encrypter,
            JWEDecrypter decrypter,
            boolean subjectRequired,
            Logger logger) {
        this.webServer = webServer;
        this.signer = signer;
        this.verifier = verifier;
        this.encrypter = encrypter;
        this.decrypter = decrypter;
        this.subjectRequired = subjectRequired;
    }

    public String createAssetDownloadJwt(String subject, String resource, String jmxauth)
            throws JOSEException, SocketException, UnknownHostException, URISyntaxException,
                    MalformedURLException {
        String issuer = webServer.get().getHostUrl().toString();
        Date now = Date.from(Instant.now());
        Date expiry = Date.from(now.toInstant().plusSeconds(120));
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(issuer)
                        .issueTime(now)
                        .notBeforeTime(now)
                        .expirationTime(expiry)
                        .subject(subject)
                        .claim(RESOURCE_CLAIM, resource)
                        .claim(JMXAUTH_CLAIM, jmxauth)
                        .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(signer);

        JWEHeader header =
                new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                        .contentType("JWT")
                        .build();
        JWEObject jwe = new JWEObject(header, new Payload(jwt));
        jwe.encrypt(encrypter);

        return jwe.serialize();
    }

    public JWT parseAssetDownloadJwt(String rawToken)
            throws ParseException, JOSEException, BadJWTException, SocketException,
                    UnknownHostException, URISyntaxException, MalformedURLException {
        JWEObject jwe = JWEObject.parse(rawToken);
        jwe.decrypt(decrypter);

        SignedJWT jwt = jwe.getPayload().toSignedJWT();
        jwt.verify(verifier);

        // TODO extract this claims verifier
        // TODO add a SecurityContext
        String cryostatUri = webServer.get().getHostUrl().toString();
        JWTClaimsSet exactMatchClaims =
                new JWTClaimsSet.Builder().issuer(cryostatUri).audience(cryostatUri).build();
        Set<String> requiredClaimNames =
                new HashSet<>(Set.of("exp", "nbf", "iat", "iss", "aud", RESOURCE_CLAIM));
        if (subjectRequired) {
            requiredClaimNames.add("sub");
        }
        new DefaultJWTClaimsVerifier<>(cryostatUri, exactMatchClaims, requiredClaimNames)
                .verify(jwt.getJWTClaimsSet(), null);

        return jwt;
    }
}
