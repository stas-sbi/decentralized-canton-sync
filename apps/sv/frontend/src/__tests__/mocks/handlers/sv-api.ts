// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { validatorLicensesHandler, dsoInfoHandler } from 'common-test-utils';
import { rest, RestHandler } from 'msw';
import {
  ErrorResponse,
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
} from 'sv-openapi';

import { voteResults } from '../constants';

export const buildSvMock = (svUrl: string): RestHandler[] => [
  rest.get(`${svUrl}/v0/admin/authorization`, (_, res, ctx) => {
    return res(ctx.status(200));
  }),
  dsoInfoHandler(svUrl),
  rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
    return res(
      ctx.json<ListDsoRulesVoteRequestsResponse>({
        dso_rules_vote_requests: [],
      })
    );
  }),
  rest.post(`${svUrl}/v0/admin/sv/voteresults`, (_, res, ctx) => {
    console.log(voteResults);
    return res(ctx.json<ListDsoRulesVoteResultsResponse>(voteResults));
  }),
  rest.get(`${svUrl}/v0/admin/domain/cometbft/debug`, (_, res, ctx) => {
    return res(
      ctx.status(404),
      ctx.json<ErrorResponse>({
        error: `No domain nodes in this test.`,
      })
    );
  }),
  rest.get(`${svUrl}/v0/admin/domain/sequencer/status`, (_, res, ctx) => {
    return res(
      ctx.status(404),
      ctx.json<ErrorResponse>({
        error: `No domain nodes in this test.`,
      })
    );
  }),
  rest.get(`${svUrl}/v0/admin/domain/mediator/status`, (_, res, ctx) => {
    return res(
      ctx.status(404),
      ctx.json<ErrorResponse>({
        error: `No domain nodes in this test.`,
      })
    );
  }),
  validatorLicensesHandler(svUrl),
];
