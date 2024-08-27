// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// TODO(#7579) -- remove duplication from default config

const config = {
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  // auth: {
  //   algorithm: 'rs-256',
  //   authority: "",
  //   client_id: "",
  //   token_audience: 'https://validator.example.com/api',
  // },
  services: {
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: 'http://wallet.localhost:3000',
    },
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003/api/validator',
    },
  },
  spliceInstanceNames: {
    networkName: 'Ecilps',
    networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
    amuletName: 'Teluma',
    amuletNameAcronym: 'TLM',
    nameServiceName: 'Teluma Name Service',
    nameServiceNameAcronym: 'TNS',
  },
};

export { config };
