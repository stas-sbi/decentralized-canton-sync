// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useMutation } from '@tanstack/react-query';
import {
  DateDisplay,
  DisableConditionally,
  Loading,
  SvClientProvider,
  CopyableTypography,
} from 'common-frontend';
import React from 'react';

import { Button, Stack, Table, TableContainer, TableHead, Typography } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useValidatorOnboardings } from '../hooks/useValidatorOnboardings';
import { useSvConfig } from '../utils';

const ValidatorOnboardingSecrets: React.FC = () => {
  const ONBOARDING_SECRET_EXPIRY_IN_SECOND = 86400; // We allow validator to be onboarded in 24 hours
  const { prepareValidatorOnboarding } = useSvAdminClient();
  const validatorOnboardingsQuery = useValidatorOnboardings();

  const prepareOnboardingMutation = useMutation({
    mutationFn: () => {
      return prepareValidatorOnboarding(ONBOARDING_SECRET_EXPIRY_IN_SECOND);
    },
  });

  if (validatorOnboardingsQuery.isLoading) {
    return <Loading />;
  }

  if (validatorOnboardingsQuery.isError || prepareOnboardingMutation.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const validatorOnboardings = validatorOnboardingsQuery.data.sort((a, b) => {
    return new Date(b.payload.expiresAt).valueOf() - new Date(a.payload.expiresAt).valueOf();
  });

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Validator Onboarding Secrets
      </Typography>
      <DisableConditionally
        conditions={[{ disabled: prepareOnboardingMutation.isLoading, reason: 'Loading...' }]}
      >
        <Button
          id="create-validator-onboarding-secret"
          variant="pill"
          fullWidth
          size="large"
          onClick={() => prepareOnboardingMutation.mutate()}
        >
          Create a validator onboarding secret
        </Button>
      </DisableConditionally>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="onboarding-secret-table">
          <TableHead>
            <TableRow>
              <TableCell>Expires At</TableCell>
              <TableCell>Onboarding Secret</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {validatorOnboardings.map(onboarding => {
              return (
                <OnboardingRow
                  key={onboarding.payload.candidateSecret}
                  expiresAt={onboarding.payload.expiresAt}
                  secret={onboarding.payload.candidateSecret}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

interface OnboardingRowProps {
  expiresAt: string;
  secret: string;
}

const OnboardingRow: React.FC<OnboardingRowProps> = ({ expiresAt, secret }) => {
  return (
    <TableRow className="onboarding-secret-table-row">
      <TableCell>
        <DateDisplay datetime={expiresAt} />
      </TableCell>
      <TableCell>
        <CopyableTypography text={secret} className="onboarding-secret-table-secret" />
      </TableCell>
    </TableRow>
  );
};

const ValidatorOnboardingSecretsWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ValidatorOnboardingSecrets />
    </SvClientProvider>
  );
};

export default ValidatorOnboardingSecretsWithContexts;
