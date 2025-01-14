const host = window.location.hostname;
const cluster = "${CN_APP_UI_CLUSTER}";
window.splice_config = {
  auth: {
    algorithm: "rs-256",
    authority: "${CN_APP_UI_AUTH_URL}",
    client_id: "${CN_APP_UI_AUTH_CLIENT_ID}",
    token_audience: "${CN_APP_UI_AUTH_AUDIENCE}",
    token_scope: "daml_ledger_api",
  },
  services: {
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: window.location.origin.replace("splitwell", "wallet"),
    },
    splitwell: {
      // URL of the splitwell backend
      url: `https://splitwell.${cluster}/api/splitwell`,
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: "https://" + window.location.hostname + "/api/json-api/",
    },
    scan: {
      // URL of the scan app's HTTP API
      url: `https://scan.sv-2.${cluster}/api/scan`,
    },
  },
  spliceInstanceNames: {
    networkName: "${CN_APP_UI_NETWORK_NAME}",
    networkFaviconUrl: "${CN_APP_UI_NETWORK_FAVICON_URL}",
    amuletName: "${CN_APP_UI_AMULET_NAME}",
    amuletNameAcronym: "${CN_APP_UI_AMULET_NAME_ACRONYM}",
    nameServiceName: "${CN_APP_UI_NAME_SERVICE_NAME}",
    nameServiceNameAcronym: "${CN_APP_UI_NAME_SERVICE_NAME_ACRONYM}",
  },
};
