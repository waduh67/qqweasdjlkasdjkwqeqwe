-- Skema SQL FreeRADIUS (dialek PostgreSQL), versi ringkas-standar.
--
-- Skema resmi FreeRADIUS 3.x: radacct menampung akunting sesi (dari sinilah adapter
-- FreeRADIUS collector membaca sesi hidup), radcheck/radreply/rad*group* menampung
-- kredensial & atribut balasan, nas menampung daftar BRAS klien. Dijalankan otomatis
-- saat container radius-db pertama kali dibuat.

CREATE TABLE IF NOT EXISTS radacct (
    radacctid           bigserial PRIMARY KEY,
    acctsessionid       text NOT NULL,
    acctuniqueid        text NOT NULL UNIQUE,
    username            text,
    groupname           text,
    realm               text,
    nasipaddress        inet NOT NULL,
    nasportid           text,
    nasporttype         text,
    acctstarttime       timestamp with time zone,
    acctupdatetime      timestamp with time zone,
    acctstoptime        timestamp with time zone,
    acctinterval        bigint,
    acctsessiontime     bigint,
    acctauthentic       text,
    connectinfo_start   text,
    connectinfo_stop    text,
    acctinputoctets     bigint,
    acctoutputoctets    bigint,
    calledstationid     text,
    callingstationid    text,
    acctterminatecause  text,
    servicetype         text,
    framedprotocol      text,
    framedipaddress     inet,
    framedipv6address   inet,
    framedipv6prefix    inet,
    framedinterfaceid   text,
    delegatedipv6prefix inet,
    class               text
);

-- Sesi hidup = acctstoptime IS NULL; indeks parsial mempercepat query itu, yang
-- persis dipakai adapter collector tiap denyut.
CREATE INDEX IF NOT EXISTS radacct_active_session_idx ON radacct (acctuniqueid) WHERE acctstoptime IS NULL;
CREATE INDEX IF NOT EXISTS radacct_bulk_close_idx ON radacct (nasipaddress, acctstarttime) WHERE acctstoptime IS NULL;
CREATE INDEX IF NOT EXISTS radacct_username_idx ON radacct (username);

CREATE TABLE IF NOT EXISTS radcheck (
    id        serial PRIMARY KEY,
    username  text NOT NULL DEFAULT '',
    attribute text NOT NULL DEFAULT '',
    op        varchar(2) NOT NULL DEFAULT '==',
    value     text NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radcheck_username_idx ON radcheck (username, attribute);

CREATE TABLE IF NOT EXISTS radreply (
    id        serial PRIMARY KEY,
    username  text NOT NULL DEFAULT '',
    attribute text NOT NULL DEFAULT '',
    op        varchar(2) NOT NULL DEFAULT '=',
    value     text NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radreply_username_idx ON radreply (username, attribute);

CREATE TABLE IF NOT EXISTS radgroupcheck (
    id        serial PRIMARY KEY,
    groupname text NOT NULL DEFAULT '',
    attribute text NOT NULL DEFAULT '',
    op        varchar(2) NOT NULL DEFAULT '==',
    value     text NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radgroupcheck_groupname_idx ON radgroupcheck (groupname, attribute);

CREATE TABLE IF NOT EXISTS radgroupreply (
    id        serial PRIMARY KEY,
    groupname text NOT NULL DEFAULT '',
    attribute text NOT NULL DEFAULT '',
    op        varchar(2) NOT NULL DEFAULT '=',
    value     text NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radgroupreply_groupname_idx ON radgroupreply (groupname, attribute);

CREATE TABLE IF NOT EXISTS radusergroup (
    id        serial PRIMARY KEY,
    username  text NOT NULL DEFAULT '',
    groupname text NOT NULL DEFAULT '',
    priority  integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS radusergroup_username_idx ON radusergroup (username);

CREATE TABLE IF NOT EXISTS radpostauth (
    id        bigserial PRIMARY KEY,
    username  text NOT NULL,
    pass      text,
    reply     text,
    calledstationid  text,
    callingstationid text,
    authdate  timestamp with time zone NOT NULL DEFAULT now(),
    class     text
);

-- Daftar BRAS klien; dibaca FreeRADIUS bila read_clients=yes pada modul sql. Di
-- setup ini klien didefinisikan di clients.conf (dari .env), jadi tabel ini boleh kosong.
CREATE TABLE IF NOT EXISTS nas (
    id          serial PRIMARY KEY,
    nasname     text NOT NULL,
    shortname   text NOT NULL,
    type        text NOT NULL DEFAULT 'other',
    ports       integer,
    secret      text NOT NULL DEFAULT 'secret',
    server      text,
    community   text,
    description text NOT NULL DEFAULT 'RADIUS Client'
);
