package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.AuthDtos.LoginResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoLoginUrlResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoSamlAcsRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.SsoSettingsService.RuntimeSsoSettings;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
@RequiredArgsConstructor
public class SsoSamlService {
    private static final String SAML_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String SAML_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String SAML_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    private static final String BEARER = "urn:oasis:names:tc:SAML:2.0:cm:bearer";
    private final SsoSettingsService settingsService;
    private final SsoAssertionService assertionService;
    private final Map<String, Long> usedAssertionIds = new ConcurrentHashMap<>();

    public SsoLoginUrlResponse loginUrl(String tenantId, UUID companyId) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        UUID normalizedCompany = companyId == null ? CompanyContext.DEFAULT_COMPANY_ID : companyId;
        RuntimeSsoSettings sso = settingsService.runtime(normalizedTenant, normalizedCompany);
        requireSaml(sso);
        if (blank(sso.loginUrl())) {
            throw new BusinessException("SAML login URL is not configured");
        }
        String relayState = normalizedTenant + "|" + normalizedCompany;
        String url = sso.loginUrl()
            + (sso.loginUrl().contains("?") ? "&" : "?")
            + "RelayState=" + enc(relayState)
            + "&tenantId=" + enc(normalizedTenant)
            + "&companyId=" + enc(normalizedCompany.toString());
        return new SsoLoginUrlResponse(url, relayState, Instant.now().plusSeconds(Math.max(60, sso.timestampSkewSeconds())).getEpochSecond());
    }
    @Transactional
    public LoginResponse acs(SsoSamlAcsRequest request) {
        if (request == null || blank(request.samlResponse())) {
            throw new BusinessException("SAMLResponse is required");
        }
        RelayContext relayContext = relayContext(request);
        String tenantId = relayContext.tenantId();
        UUID companyId = relayContext.companyId();
        RuntimeSsoSettings sso = settingsService.runtime(tenantId, companyId);
        requireSaml(sso);
        Document document = parseSamlResponse(request.samlResponse());
        Element response = document.getDocumentElement();
        if (!"Response".equals(response.getLocalName()) || !SAML_PROTOCOL_NS.equals(response.getNamespaceURI())) {
            throw new BusinessException("Invalid SAML response root");
        }
        markIdAttributes(document);
        validateStatus(response);
        validateIssuer(response, sso);
        Element assertion = singleAssertion(document);
        validateRequiredIssuer(assertion, sso);
        validateSignature(document, response, assertion, sso);
        validateAudience(assertion, sso);
        validateDestination(response, assertion, sso);
        validateTimeWindow(assertion, sso);
        rememberAssertion(assertion, sso);
        Map<String, List<String>> attributes = attributes(assertion);
        String nameId = directChildText(directChild(assertion, SAML_ASSERTION_NS, "Subject"), SAML_ASSERTION_NS, "NameID");
        String username = firstNonBlank(attribute(attributes, "preferred_username"), attribute(attributes, "username"), attribute(attributes, "uid"), attribute(attributes, "login"), nameId);
        String email = firstNonBlank(attribute(attributes, "email"), attribute(attributes, "mail"), attribute(attributes, "emailaddress"));
        String fullName = firstNonBlank(attribute(attributes, "displayname"), attribute(attributes, "name"), attribute(attributes, "cn"), username);
        List<String> roles = roles(attributes);
        return assertionService.authenticateClaims(tenantId, companyId, username, email, fullName, roles, "SAML");
    }
    private void requireSaml(RuntimeSsoSettings sso) {
        if (!sso.enabled()) {
            throw new BusinessException("SSO is not enabled");
        }
        if (!"SAML".equalsIgnoreCase(sso.providerType())) {
            throw new BusinessException("SSO provider type is not SAML");
        }
        if (blank(sso.idpEntityId()) || blank(sso.spEntityId()) || blank(sso.acsUrl()) || blank(sso.x509Certificate())) {
            throw new BusinessException("SAML metadata, entity IDs, ACS URL and IdP certificate must be configured");
        }
    }
    private Document parseSamlResponse(String encoded) {
        try {
            byte[] xml = Base64.getMimeDecoder().decode(encoded);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception ex) {
            throw new BusinessException("Cannot parse SAMLResponse");
        }
    }
    private void validateStatus(Element response) {
        Element statusCode = firstElement(response.getElementsByTagNameNS(SAML_PROTOCOL_NS, "StatusCode"));
        if (statusCode == null || !SAML_SUCCESS.equals(statusCode.getAttribute("Value"))) {
            throw new BusinessException("SAML response status is not Success");
        }
    }
    private void validateIssuer(Element element, RuntimeSsoSettings sso) {
        String issuer = directChildText(element, SAML_ASSERTION_NS, "Issuer");
        if (!blank(issuer) && !issuer.equals(sso.idpEntityId())) {
            throw new BusinessException("SAML issuer mismatch");
        }
    }
    private void validateRequiredIssuer(Element element, RuntimeSsoSettings sso) {
        String issuer = directChildText(element, SAML_ASSERTION_NS, "Issuer");
        if (blank(issuer) || !issuer.equals(sso.idpEntityId())) {
            throw new BusinessException("SAML issuer mismatch");
        }
    }
    private Element singleAssertion(Document document) {
        NodeList assertions = document.getElementsByTagNameNS(SAML_ASSERTION_NS, "Assertion");
        if (assertions.getLength() != 1) {
            throw new BusinessException("SAML response must contain exactly one assertion");
        }
        return (Element) assertions.item(0);
    }
    private void validateSignature(Document document, Element response, Element assertion, RuntimeSsoSettings sso) {
        PublicKey publicKey = publicKey(sso.x509Certificate());
        boolean assertionSigned = directChild(assertion, XMLDSIG_NS, "Signature") != null && verifySignature(document, directChild(assertion, XMLDSIG_NS, "Signature"), assertion, publicKey);
        boolean responseSigned = directChild(response, XMLDSIG_NS, "Signature") != null && verifySignature(document, directChild(response, XMLDSIG_NS, "Signature"), response, publicKey);
        if (!assertionSigned && !responseSigned) {
            throw new BusinessException("SAML response signature is invalid");
        }
    }
    private boolean verifySignature(Document document, Element signatureElement, Element signedElement, PublicKey publicKey) {
        try {
            DOMValidateContext context = new DOMValidateContext(publicKey, signatureElement);
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            for (String idAttribute : List.of("ID", "Id", "id")) {
                if (signedElement.hasAttribute(idAttribute)) {
                    context.setIdAttributeNS(signedElement, null, idAttribute);
                }
            }
            XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            if (!signature.validate(context)) {
                return false;
            }
            String signedId = signedElement.getAttribute("ID");
            if (blank(signedId)) {
                return false;
            }
            boolean hasReference = false;
            for (Object item : signature.getSignedInfo().getReferences()) {
                hasReference = true;
                Reference reference = (Reference) item;
                String uri = reference.getURI();
                if (blank(uri) || !uri.startsWith("#") || !uri.substring(1).equals(signedId)) {
                    return false;
                }
                if (!reference.validate(context)) {
                    return false;
                }
            }
            return hasReference && document.getElementById(signedId) == signedElement;
        } catch (Exception ex) {
            return false;
        }
    }
    private void validateAudience(Element assertion, RuntimeSsoSettings sso) {
        NodeList audiences = assertion.getElementsByTagNameNS(SAML_ASSERTION_NS, "Audience");
        for (int i = 0; i < audiences.getLength(); i++) {
            if (sso.spEntityId().equals(text(audiences.item(i)))) {
                return;
            }
        }
        throw new BusinessException("SAML audience mismatch");
    }
    private void validateDestination(Element response, Element assertion, RuntimeSsoSettings sso) {
        String destination = response.getAttribute("Destination");
        if (!blank(destination) && !destination.equals(sso.acsUrl())) {
            throw new BusinessException("SAML destination mismatch");
        }
        NodeList confirmations = assertion.getElementsByTagNameNS(SAML_ASSERTION_NS, "SubjectConfirmation");
        boolean bearerFound = confirmations.getLength() == 0;
        for (int i = 0; i < confirmations.getLength(); i++) {
            Element confirmation = (Element) confirmations.item(i);
            if (!blank(confirmation.getAttribute("Method")) && !BEARER.equals(confirmation.getAttribute("Method"))) {
                continue;
            }
            bearerFound = true;
            Element data = directChild(confirmation, SAML_ASSERTION_NS, "SubjectConfirmationData");
            if (data != null && !blank(data.getAttribute("Recipient")) && !data.getAttribute("Recipient").equals(sso.acsUrl())) {
                throw new BusinessException("SAML recipient mismatch");
            }
        }
        if (!bearerFound) {
            throw new BusinessException("SAML bearer confirmation is missing");
        }
    }
    private void validateTimeWindow(Element assertion, RuntimeSsoSettings sso) {
        Instant now = Instant.now();
        long skew = Math.max(0, sso.timestampSkewSeconds());
        Element conditions = directChild(assertion, SAML_ASSERTION_NS, "Conditions");
        if (conditions != null) {
            validateInstantLowerBound(conditions.getAttribute("NotBefore"), now, skew, "SAML assertion is not yet valid");
            validateInstantUpperBound(conditions.getAttribute("NotOnOrAfter"), now, skew, "SAML assertion is expired");
        }
        NodeList data = assertion.getElementsByTagNameNS(SAML_ASSERTION_NS, "SubjectConfirmationData");
        for (int i = 0; i < data.getLength(); i++) {
            validateInstantUpperBound(((Element) data.item(i)).getAttribute("NotOnOrAfter"), now, skew, "SAML subject confirmation is expired");
        }
    }
    private void validateInstantLowerBound(String value, Instant now, long skewSeconds, String message) {
        if (!blank(value)) {
            try {
                if (now.plusSeconds(skewSeconds).isBefore(Instant.parse(value))) {
                    throw new BusinessException(message);
                }
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BusinessException("SAML timestamp is invalid");
            }
        }
    }
    private void validateInstantUpperBound(String value, Instant now, long skewSeconds, String message) {
        if (!blank(value)) {
            try {
                if (!now.minusSeconds(skewSeconds).isBefore(Instant.parse(value))) {
                    throw new BusinessException(message);
                }
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BusinessException("SAML timestamp is invalid");
            }
        }
    }
    private void rememberAssertion(Element assertion, RuntimeSsoSettings sso) {
        long now = Instant.now().getEpochSecond();
        long ttl = Math.max(300, sso.timestampSkewSeconds() * 2L);
        usedAssertionIds.entrySet().removeIf(entry -> entry.getValue() < now - ttl);
        String assertionId = assertion.getAttribute("ID");
        if (blank(assertionId)) {
            throw new BusinessException("SAML assertion ID is missing");
        }
        if (usedAssertionIds.putIfAbsent(assertionId, now) != null) {
            throw new BusinessException("SAML assertion was already used");
        }
    }
    private PublicKey publicKey(String configuredCertificate) {
        try {
            String pem = configuredCertificate.trim();
            if (pem.contains("BEGIN PUBLIC KEY")) {
                String body = pem.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
                return KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(body)));
            }
            String body = pem.replaceAll("-----BEGIN CERTIFICATE-----", "")
                .replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            return certificateFactory.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(body))).getPublicKey();
        } catch (Exception ex) {
            throw new BusinessException("Cannot read SAML IdP certificate");
        }
    }
    private Map<String, List<String>> attributes(Element assertion) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        NodeList attributes = assertion.getElementsByTagNameNS(SAML_ASSERTION_NS, "Attribute");
        for (int i = 0; i < attributes.getLength(); i++) {
            Element attribute = (Element) attributes.item(i);
            String key = normalizeAttributeName(firstNonBlank(attribute.getAttribute("Name"), attribute.getAttribute("FriendlyName")));
            if (blank(key)) {
                continue;
            }
            NodeList children = attribute.getElementsByTagNameNS(SAML_ASSERTION_NS, "AttributeValue");
            List<String> target = values.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (int j = 0; j < children.getLength(); j++) {
                String value = text(children.item(j));
                if (!blank(value)) {
                    target.add(value.trim());
                }
            }
        }
        return values;
    }
    private List<String> roles(Map<String, List<String>> attributes) {
        List<String> roles = new ArrayList<>();
        for (String key : List.of("role", "roles", "groups", "group", "memberof", "authorities")) {
            for (String value : attributes.getOrDefault(key, List.of())) {
                for (String part : value.split("[,;]")) {
                    if (!blank(part)) {
                        roles.add(part.trim().toUpperCase(Locale.ROOT).replace("ROLE_", ""));
                    }
                }
            }
        }
        return roles.isEmpty() ? List.of("VIEWER") : roles;
    }
    private void markIdAttributes(Document document) {
        NodeList elements = document.getElementsByTagName("*");
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            for (String attribute : List.of("ID", "Id", "id")) {
                if (element.hasAttribute(attribute)) {
                    String value = element.getAttribute(attribute);
                    if (!blank(value) && !seenIds.add(value)) {
                        throw new BusinessException("SAML response contains duplicate ID");
                    }
                    element.setIdAttribute(attribute, true);
                }
            }
        }
    }
    private RelayContext relayContext(SsoSamlAcsRequest request) {
        String tenantId = TenantContext.normalize(request.tenantId());
        UUID companyId = request.companyId() == null ? CompanyContext.DEFAULT_COMPANY_ID : request.companyId();
        if ((!blank(request.tenantId()) && request.companyId() != null) || blank(request.relayState())) {
            return new RelayContext(tenantId, companyId);
        }
        String[] parts = request.relayState().split("\\|", -1);
        String relayTenant = parts.length > 0 ? parts[0] : null;
        UUID relayCompany = null;
        if (parts.length > 1 && !blank(parts[1])) {
            try {
                relayCompany = UUID.fromString(parts[1]);
            } catch (Exception ignored) {
                relayCompany = null;
            }
        }
        return new RelayContext(
            blank(request.tenantId()) ? TenantContext.normalize(relayTenant) : tenantId,
            request.companyId() == null && relayCompany != null ? relayCompany : companyId
        );
    }
    private String attribute(Map<String, List<String>> attributes, String key) {
        List<String> values = attributes.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
    private String normalizeAttributeName(String name) {
        if (blank(name)) {
            return "";
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf(':'));
        return slash >= 0 ? lower.substring(slash + 1) : lower;
    }
    private Element directChild(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element element && localName.equals(element.getLocalName()) && namespace.equals(element.getNamespaceURI())) {
                return element;
            }
            child = child.getNextSibling();
        }
        return null;
    }
    private String directChildText(Element parent, String namespace, String localName) {
        return text(directChild(parent, namespace, localName));
    }
    private Element firstElement(NodeList nodes) {
        if (nodes == null || nodes.getLength() == 0) {
            return null;
        }
        return (Element) nodes.item(0);
    }
    private String text(Node node) {
        return node == null ? null : node.getTextContent() == null ? null : node.getTextContent().trim();
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private record RelayContext(String tenantId, UUID companyId) {
    }
}

