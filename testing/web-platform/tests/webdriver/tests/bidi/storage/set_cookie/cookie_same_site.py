import pytest
from .. import assert_cookie_is_set, assert_partition_key, create_cookie

pytestmark = pytest.mark.asyncio


@pytest.mark.parametrize(
    "same_site",
    [
        "strict",
        "lax",
        "none",
        "default"
    ]
)
async def test_cookie_secure(bidi_session, set_cookie, test_page, domain_value, same_site):
    set_cookie_result = await set_cookie(
        cookie=create_cookie(domain=domain_value(), same_site=same_site))

    await assert_partition_key(bidi_session, actual=set_cookie_result["partitionKey"])

    await assert_cookie_is_set(bidi_session, domain=domain_value(), same_site=same_site)
