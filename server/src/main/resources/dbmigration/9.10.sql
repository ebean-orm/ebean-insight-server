-- apply changes
create unlogged table ebean_insight.ui_login_transaction (
  expires_at                    timestamptz not null,
  version                       bigint not null,
  state_hash                    varchar(43) not null,
  nonce                         varchar not null,
  code_verifier                 varchar not null,
  return_path                   varchar not null,
  previous_session_id           varchar,
  constraint pk_ui_login_transaction primary key (state_hash)
);

create unlogged table ebean_insight.ui_session (
  access_token_expires_at       timestamptz not null,
  expires_at                    timestamptz not null,
  version                       bigint not null,
  session_hash                  varchar(43) not null,
  access_token                  varchar not null,
  refresh_token                 varchar,
  id_token                      varchar,
  constraint pk_ui_session primary key (session_hash)
);

