// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import {
  AuthProvider,
  UserProvider,
  theme,
  useUserState,
  PackageIdResolver,
} from 'common-frontend';
import { cnReplaceEqualDeep } from 'common-frontend-utils';
import { ScanClientProvider } from 'common-frontend/scan-api';
import React, { useEffect } from 'react';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import * as splitwell from '@daml.js/splitwell/lib/Splice/Splitwell';

import { SplitwellLedgerApiClientProvider } from './contexts/SplitwellLedgerApiContext';
import { SplitwellClientProvider } from './contexts/SplitwellServiceContext';
import './index.css';
import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import Root from './routes/root';
import { useConfig } from './utils/config';

// We only support splitwell upgrades in the backend.
class SplitwellPackageIdResolver extends PackageIdResolver {
  async resolveTemplateId(templateId: string): Promise<string> {
    switch (this.getQualifiedName(templateId)) {
      case 'Splice.Splitwell:SplitwellRules': {
        return splitwell.SplitwellRules.templateId;
      }
      default: {
        throw new Error(`Unknown temmplate id: ${templateId}`);
      }
    }
  }
}

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        structuralSharing: cnReplaceEqualDeep,
      },
      mutations: {
        retry: (failureCount, error) =>
          // We only retry certain JSON API errors. Retrying everything is more confusing than helpful
          // because that then also retries on invalid user input.
          // The status field is defined as part of LedgerError in @daml/ledger which is thrown on JSON API errors.
          /* eslint-disable @typescript-eslint/no-explicit-any */
          [404, 409].includes((error as any).status) && failureCount < 10,
        retryDelay: 500,
      },
    },
  });

  const config = useConfig();

  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
          <SplitwellClientProvider url={config.services.splitwell.url}>
            <ScanClientProvider url={config.services.scan.url}>
              <SplitwellLedgerApiClientProvider
                jsonApiUrl={config.services.jsonApi.url}
                packageIdResolver={new SplitwellPackageIdResolver()}
              >
                {children}
              </SplitwellLedgerApiClientProvider>
            </ScanClientProvider>
          </SplitwellClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const SplitwellAuthCheck: React.FC = () => {
  const config = useConfig();
  const { loginWithOidc, oidcAuthState } = useUserState();
  useEffect(() => {
    // Auth-login after the user launched the app from their app manager.
    if (
      config.appManager &&
      oidcAuthState &&
      !oidcAuthState.isLoading &&
      !oidcAuthState.isAuthenticated
    ) {
      loginWithOidc();
    }
  }, [loginWithOidc, oidcAuthState, config]);
  return <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />;
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route
      element={
        <Providers>
          <SplitwellAuthCheck />
        </Providers>
      }
    >
      <Route path="/" element={<Root />}>
        <Route index element={<Home />} />
      </Route>
    </Route>
  )
);

const App: React.FC = () => (
  <ThemeProvider theme={theme}>
    <HelmetProvider>
      <Helmet>
        <title>Splitwell Sample Application</title>
        <meta name="description" content="Splitwell Sample Application" />
      </Helmet>
      <CssBaseline />
      <RouterProvider router={router} />
    </HelmetProvider>
  </ThemeProvider>
);

export default App;
