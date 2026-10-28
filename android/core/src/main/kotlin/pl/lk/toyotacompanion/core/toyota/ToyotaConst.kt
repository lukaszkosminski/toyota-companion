package pl.lk.toyotacompanion.core.toyota

/**
 * Toyota Connected Services (EU) constants.
 *
 * Ported from pytoyoda v6.0.0 `pytoyoda/const.py` (MIT; see core/NOTICE).
 * Toyota/Lexus EU only — Subaru realm intentionally NOT ported.
 */
object ToyotaConst {
    /** pytoyoda const.py:4 */
    const val CLIENT_VERSION = "2.14.0"

    /** pytoyoda const.py:7 (scheme normalized to lowercase; functionally identical) */
    const val API_BASE_URL = "https://ctpa-oneapi.tceu-ctp-prd.toyotaconnectedeurope.io"

    /** pytoyoda const.py:10 */
    const val CLIENT_ID = "oneapp"

    /** pytoyoda const.py:11 */
    const val REDIRECT_URI = "com.toyota.oneapp:/oauth2Callback"

    /** pytoyoda const.py:12 */
    const val BASIC_AUTH = "basic b25lYXBwOm9uZWFwcA==" // ktlint-disable-line no-verify-secrets: public OAuth client id:secret pair shipped in the official app

    /** pytoyoda const.py:13 */
    const val JWT_AUDIENCE = "oneappsdkclient"

    /** pytoyoda const.py:16-18 (realm: tme) */
    const val ACCESS_TOKEN_URL =
        "https://b2c-login.toyota-europe.com/oauth2/realms/root/realms/tme/access_token"

    /** pytoyoda const.py:19 */
    const val AUTHENTICATE_URL =
        "https://b2c-login.toyota-europe.com/json/realms/root/realms/tme/authenticate?authIndexType=service&authIndexValue=oneapp"

    /** pytoyoda const.py:20 */
    const val AUTHORIZE_URL =
        "https://b2c-login.toyota-europe.com/oauth2/realms/root/realms/tme/authorize?client_id=oneapp" +
            "&scope=openid+profile+write&response_type=code" +
            "&redirect_uri=com.toyota.oneapp:/oauth2Callback&code_challenge=plain&code_challenge_method=plain"

    /** Shared public API key required by the gateway; pytoyoda const/controller `_prepare_headers` :516-517 */
    const val X_API_KEY = "tTZipv6liF74PwMfk9Ed68AQ0bISswwf3iHQdqcF"

    /** pytoyoda const.py:36 */
    const val VEHICLE_GUID_ENDPOINT = "/v2/vehicle/guid"

    /** pytoyoda const.py:61 */
    const val TELEMETRY_ENDPOINT = "/v3/telemetry"

    /** pytoyoda const.py:65 */
    const val TRIPS_ENDPOINT = "/v1/trips?from={from_date}&to={to_date}&summary={summary}&limit={limit}&offset={offset}"

    /** pytoyoda const.py:66 */
    const val TRIP_DETAIL_ENDPOINT = "/v1/trips/{trip_id}"

    /** pytoyoda const.py:84 — ml -> l divisor */
    const val ML_TO_L_FACTOR = 1000.0

    /** pytoyoda const.py:86 — miles -> km factor */
    const val MILES_TO_KM_FACTOR = 1.60934
}
