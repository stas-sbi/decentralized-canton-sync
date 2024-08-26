// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, ErrorBoundary, ErrorRouterPage, UserProvider, theme } from 'common-frontend';
import { cnReplaceEqualDeep } from 'common-frontend-utils';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  Route,
  RouterProvider,
  createBrowserRouter,
  createRoutesFromElements,
  useNavigate,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

import { SvAdminClientProvider } from './contexts/SvAdminServiceContext';
import AmuletPrice from './routes/amuletPrice';
import AuthCheck from './routes/authCheck';
import Dso from './routes/dso';
import Leader from './routes/leader';
import Root from './routes/root';
import ValidatorOnboarding from './routes/validatorOnboarding';
import Voting from './routes/voting';
import { config } from './utils';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const navigate = useNavigate();
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        structuralSharing: cnReplaceEqualDeep,
      },
    },
    logger: {
      log: () => {},
      error: () => {},
      warn: () => {},
    },
  });
  return (
    <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
      <QueryClientProvider client={queryClient}>
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <SvAdminClientProvider url={config.services.sv.url}>
            <LocalizationProvider dateAdapter={AdapterDayjs}>{children}</LocalizationProvider>
          </SvAdminClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route
      errorElement={<ErrorRouterPage />}
      element={
        <Providers>
          <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
        </Providers>
      }
    >
      <Route path="/" element={<Root />}>
        <Route index element={<Dso />} />
        <Route path="dso" element={<Dso />} />
        <Route path="validator-onboarding" element={<ValidatorOnboarding />} />
        <Route path="cc-price" element={<AmuletPrice />} />
        <Route path="votes" element={<Voting />} />
        <Route path="leader" element={<Leader />} />
      </Route>
    </Route>
  )
);

const App: React.FC = () => (
  <ErrorBoundary>
    <ThemeProvider theme={theme}>
      <HelmetProvider>
        <Helmet>
          <title>Super Validator Operations</title>
          <meta name="description" content="Super Validator Operations" />
        </Helmet>
        <CssBaseline />
        <RouterProvider router={router} />
      </HelmetProvider>
    </ThemeProvider>
  </ErrorBoundary>
);

export default App;
