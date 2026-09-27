--liquibase formatted sql

--changeset team:002-index
CREATE INDEX IX_KLANT_NAAM ON KLANT (NAAM);

--changeset team:002-betaalmethodes
INSERT INTO BETAALMETHODE (ID, CODE, ACTIEF, SORTERING) VALUES ('3f2b8c1e-8d4a-4c7e-9b1a-2e6f5d4c3b2a', 'IDEAL', 1, 1);
INSERT INTO BETAALMETHODE (ID, CODE, ACTIEF, SORTERING) VALUES ('7a1c9e2d-4b3f-4e8a-a6d5-1c2b3a4f5e6d', 'ACCEPTGIRO', 1, 2);
