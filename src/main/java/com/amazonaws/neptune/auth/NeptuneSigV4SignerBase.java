/*
 *   Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazonaws.neptune.auth;

import com.amazonaws.DefaultRequest;
import com.amazonaws.SignableRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.http.HttpMethodName;
import com.amazonaws.util.SdkHttpUtils;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.amazonaws.auth.internal.SignerConstants.AUTHORIZATION;
import static com.amazonaws.auth.internal.SignerConstants.HOST;
import static com.amazonaws.auth.internal.SignerConstants.X_AMZ_DATE;

/**
 * Base implementation of {@link NeptuneSigV4Signer} interface.
 * <p>
 * This implementation uses the internal AWS SDK signer to sign requests. The constructor
 * requires the region name for which to sign as well as an {@link AWSCredentialsProvider}
 * providing access to the credentials used for signing the request. The service name used
 * within the signing process is hardcoded to be "neptune-db", which is the official name
 * of the Amazon Neptune service.
 * <p>
 * The implementation uses the following approach for signing the request:
 * <ol>
 *     <li>Convert the input request nto an AWS SDK {@link SignableRequest}.</li>
 *     <li>Sign the {@link SignableRequest} using an AWS SDK {@link AWS4Signer}</li>
 *     <li>\Attach the computed authorization headers to the input request, thus signing it</li>
 * </ol>
 *
 * The signed request can then be sent to an IAM authorized Neptune instance.
 *
 * @param <T> type of the request to be signed
 * @author schmdtm
 */
public abstract class NeptuneSigV4SignerBase<T> implements NeptuneSigV4Signer<T> {

    /**
     * This signer is supposed to be use in combination with Amazon Neptune.
     */
    private static final String NEPTUNE_SERVICE_NAME = "neptune-db";

    /**
     * The AWS credentials provider, providing access to the credentials.
     * This needs to be provided by the caller when initializing the signer.
     */
    private final AWSCredentialsProvider awsCredentialsProvider;

    /**
     * The {@link AWS4Signer} used internally to compute the request signature.
     */
    private final AWS4Signer aws4Signer;


    /**
     * Create a {@link NeptuneSigV4Signer} instance for the given region and service name.
     *
     * @param regionName name of the region for which the request is signed
     * @param awsCredentialsProvider the provider offering access to the credentials used for signing the request
     * @throws NeptuneSigV4SignerException in case initialization fails
     */
    public NeptuneSigV4SignerBase(
            final String regionName, final AWSCredentialsProvider awsCredentialsProvider)
            throws NeptuneSigV4SignerException {

        checkNotNull(regionName, "The region name must not be null");
        checkNotNull(awsCredentialsProvider, "The credentials provider must not be null");

        this.awsCredentialsProvider = awsCredentialsProvider;

        // initialize the signer delegate
        // => note that using the signer with multiple threads is safe as long as we do not
        //    change the configuration; so what we do here is setting the configuration on init
        //    and, forthon, will only call the aws4Signer.sign() method
        aws4Signer = new AWS4Signer();
        aws4Signer.setRegionName(regionName);
        aws4Signer.setServiceName(NEPTUNE_SERVICE_NAME);

    }

    /**
     * Convert the native request into an AWS SDK {@link SignableRequest} object which
     * can be used to perform signing. This means that the information from the request relevant
     * for signing (such as request URI, query string, headers, etc.) need to be extracted from
     * the native request and mapped to a {@link SignableRequest} object, which is used internally
     * for the signing process.
     * <p>
     * Note that the signable request internally, during the signing process, adds a "Host" header.
     * This may lead to problems if the original request has a host header with a name in different
     * capitalization (e.g. "host"), leading to duplicate host headers and the signing process to fail.
     * Hence, when using the API you need to make sure that there is either no host header in your
     * original request or the host header uses the exact string "Host" as the header name. The easiest
     * solution, if you have control over the native HTTP request, is to just leave out the host
     * header when translating and create one when signing (the host header value will be part of
     * the struct returned from the signing process).
     *
     * @param nativeRequest the native HTTP request
     * @return the {@link SignableRequest}
     * @throws NeptuneSigV4SignerException in case something goes wrong during translation
     */
    protected abstract SignableRequest<?> toSignableRequest(final T nativeRequest) throws NeptuneSigV4SignerException;

    /**
     * Attach the signature provided in the signature object to the nativeRequest.
     * More precisely, the signature contains two headers, X-AMZ-DATE and an Authorization
     * header, which need to be attached to the native HTTP request as HTTP headers or query string depending on the
     * type of signature requested - header/pre-signed url.
     *
     * @param nativeRequest the native HTTP request
     * @param signature the signature information to attach
     * @throws NeptuneSigV4SignerException in case something goes wrong during signing of the native request
     */
    protected abstract void attachSignature(final T nativeRequest, final NeptuneSigV4Signature signature)
            throws NeptuneSigV4SignerException;


    /**
     * Main logics to sign the request. The scheme is to convert the request into a
     * signable request using toSignableRequest, then sign it using the AWS SDK, and
     * finally attach the signature headers to the original request using attachSignature.
     * <p>
     * Note that toSignableRequest and attachSignature are abstract classes in
     * this base class, they require dedicated implementations depending on the type of
     * the native HTTP request.
     *
     * @param request the request to be signed
     * @throws NeptuneSigV4SignerException
     */
    @Override
    public void signRequest(final T request) throws NeptuneSigV4SignerException {

        try {

            // 1. Convert the Apache Http request into an AWS SDK signable request
            //    => to be implemented in subclass
            final SignableRequest<?> awsSignableRequest = toSignableRequest(request);

            // 2. Sign the AWS SDK signable request (which internally adds some HTTP headers)
            //    => generic, using the AWS SDK signer
            aws4Signer.sign(awsSignableRequest, awsCredentialsProvider.getCredentials());
            final NeptuneSigV4Signature signature =
                    new NeptuneSigV4Signature(
                            awsSignableRequest.getHeaders().get(HOST),
                            awsSignableRequest.getHeaders().get(X_AMZ_DATE),
                            awsSignableRequest.getHeaders().get(AUTHORIZATION));

            // 3. Copy over the Signature V4 headers to the original request
            //    => to be implemented in subclass
            attachSignature(request, signature);

        } catch (final Throwable t) {

            throw new NeptuneSigV4SignerException(t);

        }
    }

    /**
     * Helper method to create an AWS SDK {@link SignableRequest} based on HTTP information.
     * None of the information passed in here must be null. Can (yet must not) be used by
     * implementing classes.
     * <p>
     * Also note that the resulting request will not yet be actually signed; this is really
     * only a helper to convert the relevant information from the original HTTP request into
     * the AWS SDK's internal format that will be used for computing the signature in a later
     * step, see the signRequest method for details.
     *
     * @param httpMethodName name of the HTTP method (e.g. "GET", "POST", ...)
     * @param httpEndpointUri URI of the endpoint to which the HTTP request is sent. E.g. http://[host]:port/
     * @param resourcePath the resource path of the request. /resource/id is the path in http://[host]:port/resource/id
     * @param httpHeaders the headers, defined as a mapping from keys (header name) to values (header values)
     * @param httpParameters the parameters, defined as a mapping from keys (parameter names) to a list of values
     * @param httpContent the content carried by the HTTP request; use an empty InputStream for GET requests
     *
     * @return the resulting AWS SDK signable request
     * @throws NeptuneSigV4SignerException in case something goes wrong signing the request
     */
    protected SignableRequest<?> convertToSignableRequest(
            final String httpMethodName,
            final URI httpEndpointUri,
            final String resourcePath,
            final Map<String, String> httpHeaders,
            final Map<String, List<String>> httpParameters,
            final InputStream httpContent) throws NeptuneSigV4SignerException {

        checkNotNull(httpMethodName, "Http method name must not be null");
        checkNotNull(httpEndpointUri, "Http endpoint URI must not be null");
        checkNotNull(httpHeaders, "Http headers must not be null");
        checkNotNull(httpParameters, "Http parameters must not be null");
        checkNotNull(httpContent, "Http content name must not be null");

        // create the HTTP AWS SDK Signable Request and carry over information
        final DefaultRequest<?> awsRequest = new DefaultRequest(NEPTUNE_SERVICE_NAME);
        awsRequest.setHttpMethod(HttpMethodName.fromValue(httpMethodName));
        awsRequest.setEndpoint(httpEndpointUri);
        awsRequest.setResourcePath(resourcePath);
        awsRequest.setHeaders(httpHeaders);
        awsRequest.setParameters(httpParameters);
        awsRequest.setContent(httpContent);

        return awsRequest;
    }


    /**
     * Extracts the parameters from a query string (such as param1=value1&param2=value2&...).
     * The same parameter name may occur multiple times (e.g. param1 might actually be the
     * same string value as param2). The result is represented as a map from unique key
     * names to a list of their values. The query string may be null, in which case an
     * empty map is returned.
     *
     * @param queryStr the query string from which parameters are extracted
     * @return a hash map, mapping parameters by name to a list of values
     */
    protected Map<String, List<String>> extractParametersFromQueryString(final String queryStr) {

        final Map<String, List<String>> parameters = new HashMap<>();

        // convert the parameters to the internal API format
        if (queryStr != null) {
            for (final String queryParam : queryStr.split("&")) {

                if (!queryParam.isEmpty()) {

                    final String[] keyValuePair = queryParam.split("=", 2);

                    // parameters are encoded in the HTTP request, we need to decode them here
                    final String key = SdkHttpUtils.urlDecode(keyValuePair[0]);
                    final String value;

                    if (keyValuePair.length == 2) {
                        value = SdkHttpUtils.urlDecode(keyValuePair[1]);
                    } else {
                        value = "";
                    }

                    // insert the parameter key into the map, if not yet present
                    if (!parameters.containsKey(key)) {
                        parameters.put(key, new ArrayList<>());
                    }

                    // append the parameter value to the list for the given key
                    parameters.get(key).add(value);
                }
            }
        }

        return parameters;
    }

    /**
     * Tiny helper function to assert that the object is not null. In case it is null,
     * a {@link NeptuneSigV4SignerException} is thrown, with the specified error message.
     *
     * @param obj the object to be checked for null
     * @param errMsg the error message to be propagated in case the check fails
     *
     * @throws NeptuneSigV4SignerException if the check fails
     */
    protected void checkNotNull(final Object obj, final String errMsg) throws NeptuneSigV4SignerException {

        if (obj == null) {
            throw new NeptuneSigV4SignerException(errMsg);
        }

    }

    /**
     * Simple struct encapsulating pre-computed Signature V4 signing information.
     */
    public static class NeptuneSigV4Signature {

        /**
         * Value of the Host header to be used to sign the request.
         */
        private final String hostHeader;

        /**
         * Value of the X-AMZ-DATE header to be used to sign the request.
         */
        private final String xAmzDateHeader;

        /**
         * Value of the Authorization header to be used to sign the request.
         */
        private final String authorizationHeader;


        /**
         * Constructor.
         *
         * @param hostHeader the host header value used when signing the request
         * @param xAmzDateHeader string value of the xAmzDateHeader used for signing the request
         * @param authorizationHeader string value of the authorization header used for signing the request
         */
        public NeptuneSigV4Signature(
                final String hostHeader, final String xAmzDateHeader, final String authorizationHeader) {
            this.hostHeader = hostHeader;
            this.xAmzDateHeader = xAmzDateHeader;
            this.authorizationHeader = authorizationHeader;
        }

        /**
         * @return the Host header value
         */
        public String getHostHeader() {
            return hostHeader;
        }

        /**
         * @return the X-AMZ-DATE header value
         */
        public String getXAmzDateHeader() {
            return xAmzDateHeader;
        }

        /**
         * @return the Authorization header value
         */
        public String getAuthorizationHeader() {
            return authorizationHeader;
        }
    }
}
