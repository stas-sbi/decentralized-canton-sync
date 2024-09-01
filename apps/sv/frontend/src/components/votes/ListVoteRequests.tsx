// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading, SvClientProvider } from 'common-frontend';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useEffect, useMemo, useState } from 'react';

import { ClickAwayListener } from '@mui/base';
import CloseIcon from '@mui/icons-material/Close';
import {
  Box,
  Card,
  CardHeader,
  IconButton,
  Modal,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import Container from '@mui/material/Container';

import {
  ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import { useDsoInfos } from '../../contexts/SvContext';
import { useListDsoRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { useListVotes } from '../../hooks/useListVotes';
import { useSvConfig } from '../../utils';
import { ListVoteRequestsFilterTable } from './VoteRequestFilterTable';
import VoteRequestModalContent from './VoteRequestModalContent';
import { VoteResultModalContent } from './VoteResultModalContent';
import { VoteResultsFilterTable } from './VoteResultsFilterTable';

dayjs.extend(utc);

function tabProps(info: string) {
  return {
    id: `information-tab-${info}`,
    'aria-controls': `information-panel-${info}`,
  };
}

interface TabPanelProps {
  children?: React.ReactNode;
  index: number;
  value: number;
}

const TabPanel = (props: TabPanelProps) => {
  const { children, value, index, ...other } = props;
  return (
    <div
      role="tabpanel"
      hidden={value !== index}
      id={`simple-tabpanel-${index}`}
      aria-labelledby={`simple-tab-${index}`}
      {...other}
    >
      {value === index && <Box sx={{ p: 3 }}>{children}</Box>}
    </div>
  );
};

const ListVoteRequests: React.FC = () => {
  const [value, setValue] = React.useState(0);
  const [now, setNow] = useState<string>(dayjs().utc().format('YYYY-MM-DDTHH:mm:ss[Z]'));

  useEffect(() => {
    const interval = setInterval(() => {
      setNow(dayjs().utc().format('YYYY-MM-DDTHH:mm:ss[Z]'));
    }, 500);

    return () => clearInterval(interval);
  }, []);

  const handleChange = (_event: React.SyntheticEvent, newValue: number) => {
    setValue(newValue);
  };

  const listVoteRequestsQuery = useListDsoRulesVoteRequests();

  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.payload.trackingCid || v.contractId)
    : [];
  const votesQuery = useListVotes(voteRequestIds);
  const dsoInfosQuery = useDsoInfos();

  const [voteRequestContractId, setVoteRequestContractId] = useState<
    ContractId<VoteRequest> | undefined
  >(undefined);
  const [action, setAction] = useState<ActionRequiringConfirmation | undefined>(undefined);
  const [isVoteRequestModalOpen, setVoteRequestModalOpen] = useState<boolean>(false);
  const [isVoteResultModalOpen, setVoteResultModalOpen] = useState<boolean>(false);

  const openModalWithVoteRequest = (voteRequestContractId: ContractId<VoteRequest>) => {
    setVoteRequestContractId(voteRequestContractId);
    setVoteRequestModalOpen(true);
  };

  const openModalWithVoteResult = (action: ActionRequiringConfirmation) => {
    setAction(action);
    setVoteResultModalOpen(true);
  };

  const handleClose = () => {
    setVoteRequestModalOpen(false);
    setVoteResultModalOpen(false);
  };

  const svPartyId = dsoInfosQuery.data?.svPartyId;

  const alreadyVotedRequestIds = useMemo(() => {
    return svPartyId && votesQuery.data
      ? new Set(votesQuery.data.filter(v => v.voter === svPartyId).map(v => v.requestCid))
      : new Set();
  }, [votesQuery.data, svPartyId]);

  if (listVoteRequestsQuery.isLoading || dsoInfosQuery.isLoading || votesQuery.isLoading) {
    return <Loading />;
  }

  if (listVoteRequestsQuery.isError || dsoInfosQuery.isError || votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const voteRequests = listVoteRequestsQuery.data.sort((a, b) => {
    const createdAtA = a.createdAt;
    const createdAtB = b.createdAt;
    if (createdAtA === createdAtB) {
      return 0;
    } else if (createdAtA < createdAtB) {
      return 1;
    } else {
      return -1;
    }
  });

  const voteRequestsNotVoted = voteRequests.filter(
    v => !alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId)
  );
  const voteRequestsVoted = voteRequests.filter(v =>
    alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId)
  );

  function getAction(action: ActionRequiringConfirmation) {
    if (action.tag === 'ARC_DsoRules') {
      const dsoRulesAction = action.value.dsoAction;
      switch (dsoRulesAction.tag) {
        case 'SRARC_OffboardSv': {
          return `${dsoRulesAction.tag}`;
        }
        case 'SRARC_GrantFeaturedAppRight': {
          return `${dsoRulesAction.tag}`;
        }
        case 'SRARC_RevokeFeaturedAppRight': {
          return `${dsoRulesAction.tag}`;
        }
        case 'SRARC_SetConfig': {
          return `${dsoRulesAction.tag}`;
        }
        case 'SRARC_UpdateSvRewardWeight': {
          return `${dsoRulesAction.tag}`;
        }
      }
    } else if (action.tag === 'ARC_AmuletRules') {
      const amuletRulesAction = action.value.amuletRulesAction;
      switch (amuletRulesAction.tag) {
        default: {
          return `${amuletRulesAction.tag}`;
        }
      }
    }
    return 'Action tag not defined.';
  }

  return (
    <Stack>
      <Typography mt={4} variant="h4">
        Vote Requests
      </Typography>
      <Box mt={4} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs value={value} onChange={handleChange} aria-label="json tabs">
          <Tab
            label="Action Needed"
            {...tabProps('action-needed')}
            id={'tab-panel-action-needed'}
          />
          <Tab label="In Progress" {...tabProps('in-progress')} id={'tab-panel-in-progress'} />
          <Tab label="Planned" {...tabProps('planned')} id={'tab-panel-planned'} />
          <Tab label="Executed" {...tabProps('executed')} id={'tab-panel-executed'} />
          <Tab label="Rejected" {...tabProps('rejected')} id={'tab-panel-rejected'} />
        </Tabs>
      </Box>
      <TabPanel value={value} index={0}>
        <ListVoteRequestsFilterTable
          voteRequests={voteRequestsNotVoted}
          getAction={getAction}
          openModalWithVoteRequest={openModalWithVoteRequest}
          tableBodyId={'sv-voting-action-needed-table-body'}
        />
      </TabPanel>
      <TabPanel value={value} index={1}>
        <ListVoteRequestsFilterTable
          voteRequests={voteRequestsVoted}
          getAction={getAction}
          openModalWithVoteRequest={openModalWithVoteRequest}
          tableBodyId={'sv-voting-in-progress-table-body'}
        />
      </TabPanel>
      <TabPanel value={value} index={2}>
        <VoteResultsFilterTable
          getAction={getAction}
          tableBodyId={'sv-vote-results-planned-table-body'}
          tableType={'Planned'}
          openModalWithVoteResult={openModalWithVoteResult}
          validityColumnName={'Effective At'}
          accepted
          effectiveFrom={now}
        />
      </TabPanel>
      <TabPanel value={value} index={3}>
        <VoteResultsFilterTable
          getAction={getAction}
          tableBodyId={'sv-vote-results-executed-table-body'}
          tableType={'Executed'}
          openModalWithVoteResult={openModalWithVoteResult}
          accepted
        />
      </TabPanel>
      <TabPanel value={value} index={4}>
        <VoteResultsFilterTable
          getAction={getAction}
          tableBodyId={'sv-vote-results-rejected-table-body'}
          tableType={'Rejected'}
          openModalWithVoteResult={openModalWithVoteResult}
          validityColumnName={'Rejected At'}
          accepted={false}
        />
      </TabPanel>
      <Modal
        open={isVoteRequestModalOpen}
        onClose={handleClose}
        aria-labelledby="vote-request-modal-title"
        aria-describedby="vote-request-modal-description"
        slotProps={{ root: { id: 'vote-request-modal-root' } }}
      >
        <Box sx={{ flex: 1, overflowY: 'scroll', maxHeight: '100%' }}>
          <ClickAwayListener onClickAway={handleClose}>
            <Container maxWidth="md" sx={{ marginTop: '64px' }}>
              <Card variant="elevation" sx={{ backgroundColor: '#2F2F2F' }}>
                <CardHeader
                  title="Vote Request"
                  action={
                    <IconButton id="vote-request-modal-close-button" onClick={handleClose}>
                      <CloseIcon />
                    </IconButton>
                  }
                />
                <VoteRequestModalContent
                  voteRequestContractId={voteRequestContractId}
                  handleClose={handleClose}
                />
              </Card>
            </Container>
          </ClickAwayListener>
        </Box>
      </Modal>
      <Modal
        open={isVoteResultModalOpen}
        onClose={handleClose}
        aria-labelledby="vote-result-modal-title"
        aria-describedby="vote-result-modal-description"
        slotProps={{ root: { id: 'vote-result-modal-root' } }}
      >
        <Box sx={{ flex: 1, overflowY: 'scroll', maxHeight: '100%' }}>
          <ClickAwayListener onClickAway={handleClose}>
            <Container maxWidth="md" sx={{ marginTop: '64px' }}>
              <Card variant="elevation" sx={{ backgroundColor: '#2F2F2F' }}>
                <CardHeader
                  title="Vote Result"
                  action={
                    <IconButton id="vote-result-modal-close-button" onClick={handleClose}>
                      <CloseIcon />
                    </IconButton>
                  }
                />
                <VoteResultModalContent action={action} />
              </Card>
            </Container>
          </ClickAwayListener>
        </Box>
      </Modal>
    </Stack>
  );
};

const ListVoteRequestsWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ListVoteRequests />
    </SvClientProvider>
  );
};

export default ListVoteRequestsWithContexts;
