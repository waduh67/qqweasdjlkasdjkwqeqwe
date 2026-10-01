CREATE TABLE platform_acs_setting (
    id uuid PRIMARY KEY CHECK (id = '00000000-0000-4000-8000-000000000001'),
    version uuid NOT NULL,
    nbi_url varchar(2048) NOT NULL,
    username varchar(255) NOT NULL,
    password text,
    cwmp_url varchar(2048) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
